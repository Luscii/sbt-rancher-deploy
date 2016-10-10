enablePlugins(nl.focuscura.sbtrancherdeploy.RancherDeploymentPlugin)
lazy val baseSettings = Seq(
  version := "0.1",
  organization := "foo",
  rancherDeployDryRun := true
)

lazy val root = project.in(file("."))
    .settings(baseSettings)
    .aggregate(`subproject-1`, `subproject-2`, `subproject-without-deployment`)
    .settings(
      rancherShouldFinishUpgrade := {
        streams.value.log.info("This is a dummy integration test that succeeds")
        true
      },
      verifyDeploymentResult := {
        val deploymentResult = rancherDeploymentResult.value
        assert(deploymentResult == Some(RancherDeploymentResult.Finished), s"expected ${Some(RancherDeploymentResult.Finished)}, actual $deploymentResult")
        streams.value.log.info("Deployment result as expected")
      }
    )

lazy val `subproject-1` = project.in(file("subproject-1"))
  .settings(baseSettings)
  .settings(
    name := "subproject-1",
    rancherServices := Seq("sp-1.1", "sp-1.2"),
    rancherDockerImage := "focuscura/sp-1"
  )
lazy val `subproject-2` = project.in(file("subproject-2"))
  .settings(baseSettings)
  .settings(
    name := "subproject-2",
    rancherServices := Seq("sp-2.1", "sp-2.2"),
    rancherDockerImage := "focuscura/sp-2"
  )

lazy val `subproject-without-deployment` = project.in(file("subproject-without-deployment"))
  .settings(baseSettings)
  .settings(name := "subproject-without-deployment")

lazy val verifyDeploymentResult = taskKey[Unit]("")
verifyDeploymentResult := {
  val deploymentResult = rancherDeploymentResult.value
  val expected = Some(RancherDeploymentResult.Finished)
  assert(deploymentResult == expected, s"expected $expected, actual $deploymentResult")
  streams.value.log.info("Deployment result as expected")
}
