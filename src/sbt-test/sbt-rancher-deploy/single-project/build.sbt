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
