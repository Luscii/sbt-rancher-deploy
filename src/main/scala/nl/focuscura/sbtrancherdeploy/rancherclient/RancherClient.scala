package nl.focuscura.sbtrancherdeploy.rancherclient

import java.net.URI

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import play.api.libs.json._
import nl.focuscura.sbtrancherdeploy.Logger
import nl.focuscura.sbtrancherdeploy.rancherclient.RancherClient.{ServiceKey, ServiceState}

import scala.util.Random

object RancherClient {

  def apply(baseUrl: String, basicAuthCredentials: Option[(String, String)], logger: Logger, dryRun: Boolean = false): RancherClient =
    if (dryRun) DryRunRancherClient(baseUrl, basicAuthCredentials, logger) else ActualRancherClient(baseUrl, basicAuthCredentials, logger)

  case class LaunchConfig(jsValue: JsValue) {
    def withImage(image: Option[String]): LaunchConfig = image.fold(this) { (imageUuid: String) =>
      val transformer = __.json.update((__ \ "imageUuid").json.put(JsString(s"docker:$imageUuid")))
      val newJsValue = jsValue.transform(transformer).get
      LaunchConfig(newJsValue)
    }
  }

  case class StackState(jsValue: JsValue) {
    def name: String = (jsValue \ "name").as[String]
    object links {
      def services: String = (jsValue \ "links" \ "services").as[String]
    }
  }

  case class ServiceState(jsValue: JsValue) {
    def name: String = (jsValue \ "name").as[String]
    def selfLink: String = (jsValue \ "links" \ "self").as[String]
    def state: String = (jsValue \ "state").as[String]
    def transitioning: Boolean = (jsValue \ "transitioning").as[String] == "yes"

    def launchConfig: LaunchConfig = LaunchConfig((jsValue \ "launchConfig").as[JsValue])
    object actions {

      private def fromActions(field: String): String = (jsValue \ "actions" \ field).as[String]

      def upgrade: String = fromActions("upgrade")
      def rollback: String = fromActions("rollback")
      def finishUpgrade: String = fromActions("finishupgrade")
    }

  }

  trait WithName {
    def name: String
  }

  trait ServiceKey {
    def stackName: String
    def serviceName: String
    override def toString: String = s"[Service $stackName/$serviceName]"
  }

  object ServiceKey {
    def apply(stack: String, service: String): ServiceKey = new ServiceKey {
      val serviceName: String = service
      val stackName: String = stack
    }

  }

  case class ServiceRef(stackName: String, serviceName: String, serviceLink: String) extends ServiceKey with WithName  {
    def name: String = serviceName
    override def toString: String = s"[Service $stackName/$serviceName]"
  }


  case class StackRef(stackName: String, stackLink: String) extends WithName {
    def name: String = stackName
    override def toString: String = s"[Stack $stackName]"
  }

}

trait RancherClient {
  import RancherClient._
  def upgradeService(serviceRef: ServiceKey, newImage: Option[String]): Future[ServiceState]
  def finishUpgrade(serviceRef: ServiceKey): Future[ServiceState]
  def rollbackUpgrade(serviceRef: ServiceKey): Future[ServiceState]
  def close(): Unit
}

case class DryRunRancherClient(baseUrl: String, basicAuthCredentials: Option[(String, String)], logger: Logger) extends RancherClient {
  override def upgradeService(serviceRef: ServiceKey, newImage: Option[String]): Future[ServiceState] = {
    result(serviceState(serviceRef, "upgraded", transitioning = false))
  }

  override def rollbackUpgrade(serviceRef: ServiceKey): Future[ServiceState] = {
    result(serviceState(serviceRef, "active", transitioning = false))
  }

  override def close(): Unit = {}

  override def finishUpgrade(serviceRef: ServiceKey): Future[ServiceState] = {
    result(serviceState(serviceRef, "active", transitioning = false))
  }

  private def result(serviceState: ServiceState): Future[ServiceState] = {
    val delay = (new Random().nextInt(500) + 1000) * 0
    val promise = Promise[ServiceState]()
    new Thread {
      override def run() = {
        Thread.sleep(delay)
        promise.success(serviceState)
      }
    }.start()
    promise.future
  }

  private def serviceState(serviceRef: ServiceKey, state: String, transitioning: Boolean): ServiceState = {

    val jsValue = Json.parse(s"""
        {
          "name": "${serviceRef.serviceName}",
          "links": "",
          "state": "$state",
          "transitioning": "$transitioning",
          "launchConfig": {},
          "actions": {
            "upgrade": "",
            "rollback": "",
            "finishUpgrade": ""
          }
        }
    """)
    ServiceState(jsValue)
  }
}


case class ActualRancherClient(baseUrl: String, basicAuthCredentials: Option[(String, String)], logger: Logger) extends RancherClient {

  import RancherClient._

  import play.api.libs.ws._
  import play.api.libs.ws.ahc._
  import play.api.libs.ws.DefaultBodyReadables._
  import play.api.libs.ws.DefaultBodyWritables._

  val cl = getClass.getClassLoader
  val config = ConfigFactory.load(cl)
  implicit val system = ActorSystem("system", config, cl)
  implicit val materializer = ActorMaterializer()

  val client = StandaloneAhcWSClient(AhcWSClientConfigFactory.forConfig(config, cl))

  def makeRequest(path: String): StandaloneWSRequest = {
    val uri = new URI(path)
    val urlString = if (uri.isAbsolute) uri.toString else s"$baseUrl$path"
    logger.debug(s"Creating request for $path")
    val request = client.url(urlString).withRequestTimeout(2.seconds)
    basicAuthCredentials match {
      case Some((username, password)) => request.withAuth(username, password, WSAuthScheme.BASIC)
      case _ => request
    }
  }


  def getAsJson(link: String): Future[JsValue] = makeRequest(link).get() map (Json parse _.body)
  def getJsonCollection(link: String): Future[Seq[JsValue]] = getAsJson(link) map (_ \ "data") map (_.as[Seq[JsValue]])

  case class ServiceContext(serviceRef: ServiceRef) {

    def readServiceState(): Future[ServiceState] = {
      makeRequest(serviceRef.serviceLink).get() map (Json parse _.body) map (ServiceState(_))
    }

    def waitForServiceState(targetState: String): Future[ServiceState] = {
      import java.util.concurrent._
      val valuePromise = Promise[ServiceState] // this promise's future will fire when the service state is changed to "upgraded"
      val ex = new ScheduledThreadPoolExecutor(1)

      def shouldContinuePolling(): scala.concurrent.Future[Boolean] = {
        readServiceState() map { serviceState =>
          if (serviceState.transitioning) {
            logger.debug(s"$serviceRef desired state: $targetState, current: ${serviceState.state}, transitioning")
            true
          } else if (serviceState.state == targetState) {
            logger.info(s"$serviceRef desired state: $targetState reached")
            valuePromise.success(serviceState)
            false
          } else {
            logger.error(s"$serviceRef desired state: $targetState, reached unexpected state: ${serviceState.state}")
            valuePromise.failure(new Exception(s"$serviceRef desired state: $targetState, reached unexpected state: ${serviceState.state}"))
            false
          }
        } recover { case e => valuePromise.failure(e); false }
      }

      def schedule(work: => Unit): Unit = ex.schedule(new Runnable { override def run(): Unit = work }, 1, TimeUnit.SECONDS)

      def reschedule(): Unit = schedule {
        shouldContinuePolling().foreach(if (_) reschedule())
      }
      logger.info(s"$serviceRef desired state: $targetState, waiting...")
      shouldContinuePolling().foreach(if (_) reschedule())
      valuePromise.future
    }

    def finishUpgrade(serviceData: Option[ServiceState]): Future[ServiceState] = {
      serviceData map (Future(_)) getOrElse readServiceState() flatMap { serviceData =>
        makeRequest(serviceData.actions.finishUpgrade).post("")
      } map (Json parse _.body) map (ServiceState(_))
    }

    def finishUpgradeIfNeeded(): Future[ServiceState] = {
      readServiceState() flatMap { service =>
        if (service.state == "upgraded") {
          for {
            _ <- finishUpgrade(Some(service))
            newState <- waitForServiceState("active")
          } yield newState
        } else {
          Future.successful(service)
        }
      }
    }


    def rollback(): Future[ServiceState] = {
      readServiceState() flatMap { serviceData =>
        makeRequest(serviceData.actions.rollback).post("")
      } map (Json parse _.body) map (ServiceState(_))
    }

    def rollbackAndWait(): Future[ServiceState] = {
      for {
        rollingBackService <- rollback()
        rolledBackService <- waitForServiceState("active")
      } yield rolledBackService
    }

    def upgrade(newImage: Option[String]): Future[ServiceState] = {

      logger.info(s"$serviceRef upgrading with image $newImage")

      def eventualUpgradeReply(endpointForUpdate: String) = {
        readServiceState() flatMap { service =>
          val payload = Json toJson immutable.Map(
            "inServiceStrategy" -> immutable.Map(
              "launchConfig" -> service.launchConfig.withImage(newImage).jsValue
            )
          )
          makeRequest(service.actions.upgrade).addHttpHeaders(
            "Content-Type" -> "application/json"
          ) post payload.toString map (Json parse _.body) map (ServiceState(_))
        }
      }


      for {
        upgradeFinished <- finishUpgradeIfNeeded()
        upgradeReply <- eventualUpgradeReply(upgradeFinished.actions.upgrade)
        upgradeResult <- waitForServiceState("upgraded")
      } yield upgradeResult
    }
  }

  case class StackContext(stackRef: StackRef) {

    def readState(): Future[StackState] = getAsJson(stackRef.stackLink) map (StackState(_))

    def lookupServices(): Future[Seq[ServiceRef]] =
      for {
        state <- readState()
        serviceCollection <- getJsonCollection(state.links.services)
      } yield serviceCollection map { json =>
        ServiceRef(stackRef.stackName, (json \ "name").as[String], (json \ "links" \ "self").as[String])
      }
  }

  object StackContext {
    def lookupAll(): Future[Seq[StackRef]] = getJsonCollection("/v1/environments") map { jsonColl =>
      for {
        json <- jsonColl
      } yield StackRef((json \ "name").as[String], (json \ "links" \ "self").as[String])
    }
  }

  def findService(serviceRef: ServiceKey): Future[ServiceRef] = {
    def findWithName[T <: WithName](name: String)(items: Seq[T]): T = (items find (_.name == name)).get
    for {
      stack <- StackContext.lookupAll() map findWithName(serviceRef.stackName)
      service <- StackContext(stack).lookupServices() map findWithName(serviceRef.serviceName)
    } yield service
  }

  override def upgradeService(serviceRef: ServiceKey, newImage: Option[String]): Future[ServiceState] = {
    for {
      service <- findService(serviceRef) map (ServiceContext(_))
      serviceUpdated <- service upgrade newImage
    } yield serviceUpdated
  }

  override def finishUpgrade(serviceRef: ServiceKey): Future[ServiceState] = {
    for {
      service <- findService(serviceRef) map (ServiceContext(_))
      serviceUpdated <- service.finishUpgradeIfNeeded()
    } yield serviceUpdated
  }

  override def rollbackUpgrade(serviceRef: ServiceKey): Future[ServiceState] = {
    for {
      service <- findService(serviceRef) map (ServiceContext(_))
      serviceRolledBack <- service.rollbackAndWait()
    } yield serviceRolledBack
  }

  override def close(): Unit = {
    client.close()
  }
}
