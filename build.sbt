name := """fence"""

version := "0.3.1"

scalaVersion := "2.11.7"

mainClass in assembly := Some("edu.illinois.ncsa.fence.Server")

lazy val versions = new {
  val finagle = "6.35.0"
  val finatra = "2.1.6"
  val guice = "4.0"
  val logback = "1.0.13"
  val jvm = "1.7"
}

scalacOptions ++= Seq("-target:jvm-" + versions.jvm, "-feature")

javacOptions ++= Seq("-source", versions.jvm, "-target", versions.jvm)

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots"),
  "Twitter Maven" at "https://maven.twttr.com"
)


libraryDependencies ++= Seq(
  "com.twitter" %% "finagle-core" % versions.finagle,
  "com.twitter" %% "finagle-http" % versions.finagle,
  "com.twitter" %% "finagle-stats" % versions.finagle,
  "com.twitter" %% "finagle-redis" % versions.finagle,
  "com.twitter" %% "twitter-server" % "1.20.0",
  "com.typesafe" % "config" % "1.2.1",
  "org.mongodb.scala" %% "mongo-scala-driver" % "1.2.1",
  "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % "2.4.4",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.4.4",
  "com.unboundid" % "unboundid-ldapsdk" % "4.0.1",

//  "com.twitter.finatra" %% "finatra-http" % versions.finatra % "provided",
//  "com.twitter.finatra" %% "finatra-httpclient" % versions.finatra % "provided",
//  "com.twitter.finatra" %% "finatra-slf4j" % versions.finatra % "provided",
//  "com.twitter.inject" %% "inject-core" % versions.finatra % "provided",
//  "ch.qos.logback" % "logback-classic" % versions.logback % "provided",
//
//  "com.twitter.finatra" %% "finatra-http" % versions.finatra % "test",
//  "com.twitter.finatra" %% "finatra-jackson" % versions.finatra % "test",
//  "com.twitter.inject" %% "inject-server" % versions.finatra % "test",
//  "com.twitter.inject" %% "inject-app" % versions.finatra % "test",
//  "com.twitter.inject" %% "inject-core" % versions.finatra % "test",
//  "com.twitter.inject" %% "inject-modules" % versions.finatra % "test",
//  "com.google.inject.extensions" % "guice-testlib" % versions.guice % "test",
//
//  "com.twitter.finatra" %% "finatra-http" % versions.finatra % "test" classifier "tests",
//  "com.twitter.finatra" %% "finatra-jackson" % versions.finatra % "test" classifier "tests",
//  "com.twitter.inject" %% "inject-app" % versions.finatra % "test" classifier "tests",
//  "com.twitter.inject" %% "inject-core" % versions.finatra % "test" classifier "tests",
//  "com.twitter.inject" %% "inject-modules" % versions.finatra % "test" classifier "tests",
//  "com.twitter.inject" %% "inject-server" % versions.finatra % "test" classifier "tests",


  "org.mockito" % "mockito-core" % "1.9.5" % "test",
  "org.scalatest" %% "scalatest" % "2.2.3" % "test",
  "org.specs2" %% "specs2" % "2.3.12" % "test"


  //  "com.github.finagle" %% "finch-core" % "0.9.2",
//  "com.github.finagle" %% "finch-circe" % "0.9.2",
//  "io.circe" %% "circe-generic" % "0.2.1"
)

