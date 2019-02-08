import sbt.Defaults.sbtPluginExtra
import sbt._
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
      "Typesafe repository" at "https://repo.typesafe.com/typesafe/maven-releases/"
    ),

    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-ahc-ws-standalone" % "2.0.+",
      "com.typesafe.play" %% "play-json" % "2.6.+",
      "com.typesafe" % "config" % "1.+"
    ),
    libraryDependencies += sbtPluginExtra("com.typesafe.sbt" % "sbt-git" % "1.0.0", sbtV = sbtBinaryVersion.value, scalaV = scalaBinaryVersion.value)
  )
  .settings(
    publishTo := Some("Sonatype Nexus Repository Manager" at "https://nexus.focuscura.nl/repository/maven-releases/")
  )
  .settings(
    scriptedLaunchOpts := { scriptedLaunchOpts.value ++
      Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false
  )
