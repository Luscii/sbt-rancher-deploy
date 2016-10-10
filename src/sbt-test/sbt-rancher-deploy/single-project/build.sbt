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

lazy val verifyDeploymentResult = taskKey[Unit]("")
verifyDeploymentResult := {
  val deploymentResult = rancherDeploymentResult.value

  assert(deploymentResult == Some(RancherDeploymentResult.Finished), s"expected ${Some(RancherDeploymentResult.Finished)}, actual $deploymentResult")
  streams.value.log.info("Deployment result as expected")
}
