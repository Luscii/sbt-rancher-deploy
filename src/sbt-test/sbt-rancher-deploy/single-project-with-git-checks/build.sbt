enablePlugins(nl.focuscura.sbtrancherdeploy.RancherDeploymentPlugin)
version := "0.1"
organization := "foo"
name := "single-project"
rancherServices := Seq("a", "b")
rancherDockerImage := "focuscura/dummy"
rancherDeployDryRun := true
rancherShouldFinishUpgrade := {
  streams.value.log.info("This is a dummy integration test that succeeds")
  true
}


lazy val assertDeploymentResult = inputKey[Unit]("Assert deployment result is one of: failed, finished, rolled-back")
assertDeploymentResult := {
  import sbt.complete.DefaultParsers.spaceDelimited
  val args = spaceDelimited("[expected-result]").parsed
  val expectedString = args.ensuring(_.length == 1, "Pass the expected result as the argument").head
  val deploymentResult = rancherDeploymentResult.value.get
  val result = (expectedString, deploymentResult) match {
    case ("failed", RancherDeploymentResult.Failed(_)) => true
    case ("finished", RancherDeploymentResult.Finished) => true
    case ("rolled-back", RancherDeploymentResult.RolledBack) => true
    case _ => false
  }
  assert(result, s"expected $expectedString, actual $deploymentResult")
  streams.value.log.success(s"Deployment result as expected: $expectedString")
}
//lazy val verifyDeploymentResult = taskKey[Unit]("")
//verifyDeploymentResult := {
//  val deploymentResult = rancherDeploymentResult.value
//
//  assert(deploymentResult == Some(RancherDeploymentResult.Finished), s"expected ${Some(RancherDeploymentResult.Finished)}, actual $deploymentResult")
//}
