import sbt._
import Defaults._

organization := "nl.focuscura"
scalaVersion := "2.10.6"
scalacOptions ++= Seq("-unchecked", "-feature", "-explaintypes", "-deprecation")

autoCompilerPlugins := true

name := "sbt-rancher-deploy"

sbtPlugin := true

resolvers ++= Seq(
  "Typesafe Ivy releases" at "https://repo.typesafe.com/typesafe/ivy-releases",
  "Typesafe repository" at "https://repo.typesafe.com/typesafe/maven-releases/"
)


libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-ws" % "2.4.8",
  "com.typesafe.play" %% "play-json" % "2.4.8",
  "com.typesafe" % "config" % "1.3.0",

  sbtPluginExtra("se.marcuslonnberg" % "sbt-docker" % "1.4.0", sbtV = "0.13", scalaV = "2.10"),
  sbtPluginExtra("com.eed3si9n" % "sbt-assembly" % "0.14.3", sbtV = "0.13", scalaV = "2.10"),
  sbtPluginExtra("com.typesafe.sbt" % "sbt-git" % "0.8.5", sbtV = "0.13", scalaV = "2.10")
)


