package nl.focuscura.sbtrancherdeploy

//import Deployment.DeploymentConfig
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.sbt.SbtGit.git
import nl.focuscura.sbtrancherdeploy.RancherDeploymentPlugin.autoImport.RancherDeploymentResult
import nl.focuscura.sbtrancherdeploy.rancherclient.RancherClient
import sbt.Keys._
import sbt.{Command, _}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.util.Try

object RancherDeploymentPlugin extends AutoPlugin {
  override def trigger = allRequirements

  private lazy val rancherTargetEnvironment = settingKey[Option[String]]("The target rancher environment")
  private lazy val rancherDeploymentResultAttribute = AttributeKey[RancherDeploymentResult]("rancherDeploymentResult")
  object autoImport {
    lazy val rancherDeployDryRun = settingKey[Boolean]("When this is true, do a dry-run, ie. don't actually deploy")
    lazy val rancherDockerImage = taskKey[String]("Returns the docker image UUID that should be deployed")
    lazy val rancherAllowDeployment = taskKey[Boolean]("Returns true if deployment is allowed, false otherwise")
    lazy val rancherServices = taskKey[Seq[String]]("Services to upgrade once the docker image is pushed")

    lazy val rancherTest = taskKey[Unit]("")

    lazy val rancherDeploy = taskKey[RancherDeploymentResult]("Build docker image, push it to Docker hub, upgrade the services, when upgrade can be finished, do so, otherwise roll back")
    lazy val rancherUpgrade = taskKey[Unit]("Build docker image, push it to Docker hub, upgrade services returned by rancherServices")
    lazy val rancherFinishUpgrade = taskKey[RancherDeploymentResult]("Finish upgrade of services returned by rancherServices")
    lazy val rancherRollback = taskKey[RancherDeploymentResult]("Roll back upgrade of services returned by rancherServices")
    lazy val rancherShouldFinishUpgrade = taskKey[Boolean]("Returns true when the upgrade should be finished, false if it should be rolled back")
    lazy val rancherDeploymentResult = taskKey[Option[RancherDeploymentResult]]("Retrieve the result of the last deployment")

    lazy val rancherBaseSettings = Seq(
      rancherServices := Seq(),
      rancherDockerImage := "",
      (rancherAllowDeployment in Global) := Def.taskDyn[Boolean] {
        Def.taskDyn {
          val config = rancherDeploymentConfig.value
          val currentBranch = git.gitCurrentBranch.value
          if (!(config.allowAnyBranch || config.isBranchAllowed(currentBranch))) {
            throw new Exception(
              s"""Deploying current git branch $currentBranch to the "${config.environment}" environment.
                 | Allowed branches: ${config.allowedBranches.mkString(", ")}""".stripMargin)
          }
          if (!config.allowDeploymentOfUncommittedChanges && git.gitUncommittedChanges.value) {
            throw new Exception(s"""Deployment of uncommitted changes to the "${config.environment}" environment is not allowed""")
          }
          Def.task { true }
        }
      }.value,
      rancherDeployDryRun := false,
      rancherUpgrade := Def.taskDyn[Unit] {
        if (rancherServices.value.nonEmpty) {
          Def.taskDyn[Unit] {
            val dockerImage = rancherDockerImage.value
            rancherActionOnService(s"upgrade to $dockerImage") {
              _.upgradeService(_, Some(dockerImage))
            }
          }
        } else {
          Def.task[Unit] {}
        }
      }.value,
      rancherFinishUpgrade := Def.taskDyn[RancherDeploymentResult] {
        (if (rancherServices.value.nonEmpty) {
          rancherActionOnService("finish upgrade") {
            _.finishUpgrade(_)
          }
        } else {
          Def.task[Unit] {}
        }) map { _ => RancherDeploymentResult.Finished}
      }.value,

      rancherRollback := Def.taskDyn[RancherDeploymentResult] {
        (if (rancherServices.value.nonEmpty) {
          rancherActionOnService("roll back") {
            _.rollbackUpgrade(_)
          }
        } else {
          Def.task[Unit] {}
        }) map { _ => RancherDeploymentResult.RolledBack}
      }.value,
      rancherShouldFinishUpgrade := true,
      aggregate in rancherDeploy := false,
      test in rancherDeploy := (test in Test).value,
      rancherDeploy := Def.taskDyn[RancherDeploymentResult] {
        (rancherAllowDeployment in Global).value
        val p = thisProjectRef.value
        val aggregatesFilter = ScopeFilter(inAggregates(p))
        Def.taskDyn {
          val t = (test in rancherDeploy).all(aggregatesFilter).value
          Def.taskDyn {
            val aggregatedShouldFinish: Def.Initialize[Task[Boolean]] = rancherShouldFinishUpgrade.all(aggregatesFilter) map (_ forall identity)
            rancherUpgrade.all(aggregatesFilter).value
            Def.taskDyn[RancherDeploymentResult] {
              if (aggregatedShouldFinish.value) {
                rancherFinishUpgrade.all(aggregatesFilter) map {_ => RancherDeploymentResult.Finished}
              } else {
                rancherRollback.all(aggregatesFilter) map {_ => RancherDeploymentResult.RolledBack}
              }
            }
          }
        }
      }.value,
      rancherDeploymentResult := state.value.get(rancherDeploymentResultAttribute),

      commands += Command.single("rancher-deploy-to") { (state, environment) =>
        val extracted = Project.extract(state)
        val newEnvironment = (rancherTargetEnvironment in Global) := Some(environment)
        val stateWithEnvironment = extracted.append(newEnvironment, state)

        val result = Project.runTask(rancherDeploy, stateWithEnvironment, true) match {
          case None => RancherDeploymentResult.NoDeploymentDefined
          case Some((_, Inc(failure))) => RancherDeploymentResult.Failed(Option(failure.getCause))
          case Some((_, Value(v: RancherDeploymentResult))) => v
        }
        state.put(rancherDeploymentResultAttribute, result)
      }
    )

    sealed trait RancherDeploymentResult
    object RancherDeploymentResult {
      case class Failed(cause: Option[Throwable]) extends RancherDeploymentResult
      case object Finished extends RancherDeploymentResult
      case object RolledBack extends RancherDeploymentResult
      case object NoDeploymentDefined extends RancherDeploymentResult

    }
  }

  import autoImport._

  override lazy val projectSettings = rancherBaseSettings


  case class DeploymentConfig(environment: String, config: Config) {
    import scala.collection.JavaConversions._
    def allowedBranches: Set[String] = Try(config.getStringList("allowed-branches").toSet).getOrElse(Set("*"))
    def allowAnyBranch: Boolean = allowedBranches contains "*"
    def isBranchAllowed(branch: String): Boolean = allowedBranches contains branch
    def allowDeploymentOfUncommittedChanges: Boolean = Try(config.getBoolean("allow-uncommitted-changes")).getOrElse(true)
    object rancher {
      def url: String = config.getString("rancher.url")
      def stack: String = config.getString("rancher.stack")
      def basicAuthUsername: String = config.getString("rancher.basic-auth.username")
      def basicAuthPassword: String = config.getString("rancher.basic-auth.password")
    }
  }

  private def logger = Def.task[Logger] {

    val log = streams.value.log
    val projectName = projectInfo.value.nameFormal
    val taskInfo = if (rancherDeployDryRun.value) "rancher-deployment DRY RUN" else "rancher-deployment"
    def wrapMessage(message: => String) = s"[$projectName] [$taskInfo] $message"
    new Logger {
      override def debug(message: => String): Unit = log.debug(wrapMessage(message))
      override def warn(message: => String): Unit = log.warn(wrapMessage(message))
      override def error(message: => String): Unit = log.error(wrapMessage(message))
      override def info(message: => String): Unit = log.info(wrapMessage(message))
    }
  }


  private def rancherDeploymentConfig = Def.task[DeploymentConfig] {
    val configFile = (baseDirectory in ThisBuild).value / "deployment.conf"
    val config = ConfigFactory.parseFile(configFile).resolve()
    (rancherTargetEnvironment in Global).value.fold( throw new Exception("No rancherTargetEnvironment set")) { environmentName =>
      DeploymentConfig(environmentName, config.getConfig(s"environments.$environmentName"))
    }
  }


  private def rancherClient(config: DeploymentConfig, logger: Logger, dryRun: Boolean): RancherClient =
    RancherClient(config.rancher.url, Some((config.rancher.basicAuthUsername, config.rancher.basicAuthPassword)), logger, dryRun)


  private type RancherServiceAction = (RancherClient, RancherClient.ServiceKey) => Future[RancherClient.ServiceState]
  private def rancherActionOnService(actionName: String)(performApiCalls: RancherServiceAction) =
    Def.task[Unit] {
      import scala.concurrent.ExecutionContext.Implicits.global
      val deploymentConfig = rancherDeploymentConfig.value
      val rancherStack = deploymentConfig.rancher.stack
      val serviceNames = rancherServices.value

      val dryRun = rancherDeployDryRun.value
      val log = logger.value
      val rancherLocation = s"[${deploymentConfig.rancher.url} $rancherStack/${serviceNames.mkString(",")}]"
      log.info(s"$rancherLocation $actionName start")
      val upgradeResults: Future[Seq[RancherClient.ServiceState]] = Future.sequence {
        serviceNames map (RancherClient.ServiceKey(rancherStack, _)) map { serviceRef =>
          val c = rancherClient(deploymentConfig, log, dryRun = dryRun)
          log.info(s"[${deploymentConfig.rancher.url} $rancherStack/${serviceRef.serviceName}] $actionName started")
          val apiResult = performApiCalls(c, serviceRef)
          apiResult onComplete { result =>
            log.info(s"[${deploymentConfig.rancher.url} $rancherStack/${serviceRef.serviceName}] $actionName completed")
            c.close()
          }
          apiResult
        }
      }
      Await.result(upgradeResults, Duration.Inf)
      log info s"$rancherLocation $actionName succeeded"
    }
}
