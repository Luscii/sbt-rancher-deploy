import sbt._
import Defaults._
lazy val sbtRancherDeploy = (project in file("."))
  .settings(
    fork in compile := true,
    version in ThisBuild := "0.1.4",
    organization := "nl.focuscura",
    scalaVersion in ThisBuild := "2.10.6",
    scalacOptions ++= Seq("-unchecked", "-feature", "-explaintypes", "-deprecation"),

    autoCompilerPlugins := true,

    name := "sbt-rancher-deploy",

    sbtPlugin := true,

    resolvers ++= Seq(
      "Typesafe Ivy releases" at "https://repo.typesafe.com/typesafe/ivy-releases",
      "Typesafe repository" at "https://repo.typesafe.com/typesafe/maven-releases/"),


    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-ws" % "2.4.+",
      "com.typesafe.play" %% "play-json" % "2.4.+",
      "com.typesafe" % "config" % "1.+",
      sbtPluginExtra("com.typesafe.sbt" % "sbt-git" % "0.+", sbtV = "0.13", scalaV = "2.10"))
  )
