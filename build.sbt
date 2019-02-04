import sbt._
import Defaults._
lazy val sbtRancherDeploy = (project in file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    fork in compile := true,
    version in ThisBuild := "0.2",
    organization := "nl.focuscura",
    scalaVersion in ThisBuild := "2.12.7",
    scalacOptions ++= Seq("-unchecked", "-feature", "-explaintypes", "-deprecation"),

    autoCompilerPlugins := true,

    name := "sbt-rancher-deploy",

    sbtPlugin := true,

    resolvers ++= Seq(
      "Typesafe Ivy releases" at "https://repo.typesafe.com/typesafe/ivy-releases",
      "Typesafe repository" at "https://repo.typesafe.com/typesafe/maven-releases/"),


    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-ahc-ws-standalone" % "2.0.+",
      "com.typesafe.play" %% "play-json" % "2.6.+",
      "com.typesafe" % "config" % "1.+",
      sbtPluginExtra("com.typesafe.sbt" % "sbt-git" % "1.0.+", sbtV = "1.0", scalaV = "2.12")
    )
  )
  .settings(
    scriptedLaunchOpts := { scriptedLaunchOpts.value ++
      Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false
  )
