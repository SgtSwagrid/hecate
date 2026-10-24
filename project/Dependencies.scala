import sbt.*
import sbt.Keys.*

object Dependencies:

  object V:

    val tapir           = "1.14.0"
    val circe           = "0.14.17"
    val catsEffect      = "3.7.1"
    val slick           = "3.6.1"
    val h2              = "2.5.252"
    val scalajs         = "2.8.1"
    val laminar         = "17.0.0"
    val laminext        = "0.17.0"
    val munitCatsEffect = "2.2.1"
    val sttpClient      = "3.11.0"
    val angusMail       = "2.0.5"
    val greenMail       = "2.1.14"

  lazy val tapir = libraryDependencies ++= Seq(
    "com.softwaremill.sttp.tapir" %% "tapir-core"       % V.tapir,
    "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % V.tapir,
  )

  lazy val tapirStub = libraryDependencies ++= Seq(
    "com.softwaremill.sttp.tapir" %% "tapir-sttp-stub-server" % V.tapir % Test,
    "com.softwaremill.sttp.client3" %% "cats" % V.sttpClient % Test,
  )

  lazy val circe = libraryDependencies ++= Seq(
    "io.circe" %% "circe-core"   % V.circe,
    "io.circe" %% "circe-parser" % V.circe,
  )

  lazy val catsEffect = libraryDependencies ++=
    Seq("org.typelevel" %% "cats-effect" % V.catsEffect)

  lazy val database = libraryDependencies ++= Seq(
    "com.typesafe.slick" %% "slick" % V.slick,
    "com.h2database"      % "h2"    % V.h2 % Test,
  )

  lazy val mail = libraryDependencies ++= Seq(
    "org.eclipse.angus" % "angus-mail" % V.angusMail,
    "com.icegreen"      % "greenmail"  % V.greenMail % Test,
  )

  lazy val scalajs = libraryDependencies ++=
    Seq("org.scala-js" %% "scalajs-dom" % V.scalajs)

  lazy val laminar = libraryDependencies ++= Seq(
    "com.raquo"   %% "laminar"     % V.laminar,
    "io.laminext" %% "core"        % V.laminext,
    "io.laminext" %% "fetch"       % V.laminext,
    "io.laminext" %% "fetch-circe" % V.laminext,
  )

  lazy val munitCatsEffect = libraryDependencies ++=
    Seq("org.typelevel" %% "munit-cats-effect" % V.munitCatsEffect % Test)
