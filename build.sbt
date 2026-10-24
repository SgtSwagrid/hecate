import IdeSettings.packagePrefix
import org.scalajs.sbtplugin.ScalaJSPlugin
import sbt._
import sbt.Keys._
import sbtunidoc.BaseUnidocPlugin.autoImport.*
import sbtunidoc.ScalaUnidocPlugin

// Project ids carry the library's name, so as not to clash with a host's.

val scala3 = "3.9.0"

ThisBuild / scalaVersion := scala3

ThisBuild / scalacOptions ++= Seq(
  "-explain",
  "-explain-types",
  "-explain-cyclic",
)

val projectRoot = "com.alecdorrington.hecate"

lazy val hecateCore = projectMatrix
  .in(file("core"))
  .settings(
    name          := "hecate-core",
    packagePrefix := projectRoot,

    // Pinned, as a matrix resolves sources against the working directory, not this build.
    sourceDirectory := (ThisBuild / baseDirectory).value / "core" / "src",
    Dependencies.circe,
    Dependencies.munitCatsEffect,
  )
  .jvmPlatform(scalaVersions = Seq(scala3))
  .jsPlatform(scalaVersions = Seq(scala3))

lazy val hecateTapir = projectMatrix
  .in(file("tapir"))
  .dependsOn(hecateCore)
  .settings(
    name          := "hecate-tapir",
    packagePrefix := s"$projectRoot.tapir",

    // Pinned to this build's own base, as for the core.
    sourceDirectory := (ThisBuild / baseDirectory).value / "tapir" / "src",
    Dependencies.tapir,
    Dependencies.circe,
    Dependencies.munitCatsEffect,
  )
  .jvmPlatform(scalaVersions = Seq(scala3))
  .jsPlatform(scalaVersions = Seq(scala3))

lazy val hecateServer = project
  .in(file("server"))
  .dependsOn(hecateCore.jvm(scala3))
  .settings(
    name          := "hecate-server",
    packagePrefix := s"$projectRoot.server",
    Dependencies.circe,
    Dependencies.catsEffect,
    Dependencies.database,
    Dependencies.munitCatsEffect,
  )

lazy val hecateServerTapir = project
  .in(file("server-tapir"))
  .dependsOn(
    hecateServer % "compile->compile;test->test",
    hecateTapir.jvm(scala3),
  )
  .settings(
    name          := "hecate-server-tapir",
    packagePrefix := s"$projectRoot.server.tapir",
    Dependencies.tapir,
    Dependencies.catsEffect,
    Dependencies.database,
    Dependencies.tapirStub,
    Dependencies.munitCatsEffect,
  )

lazy val hecateSmtp = project
  .in(file("smtp"))
  .dependsOn(hecateServer)
  .settings(
    name          := "hecate-smtp",
    packagePrefix := s"$projectRoot.smtp",
    Dependencies.catsEffect,
    Dependencies.mail,
    Dependencies.munitCatsEffect,
  )

lazy val hecateClient = project
  .in(file("client"))
  .dependsOn(hecateCore.js(scala3))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name          := "hecate-client",
    packagePrefix := s"$projectRoot.client",
    Dependencies.scalajs,
    Dependencies.laminar,
    Dependencies.circe,
    Dependencies.munitCatsEffect,
  )

lazy val hecate = project
  .in(file("."))
  .enablePlugins(ScalaUnidocPlugin)
  .aggregate(
    (hecateCore.projectRefs ++ hecateTapir.projectRefs ++ Seq[ProjectReference](
      hecateServer,
      hecateServerTapir,
      hecateSmtp,
      hecateClient,
    )) *,
  )
  .settings(
    publish / skip := true,

    // The JVM side alone, as the JS side would document the shared sources twice.
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
      hecateCore.jvm(scala3),
      hecateTapir.jvm(scala3),
      hecateServer,
      hecateServerTapir,
      hecateSmtp,
    ),
    ScalaUnidoc / unidoc / scalacOptions ++= Seq("-project", "Hecate"),
  )
