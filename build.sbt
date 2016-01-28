name := """fence"""

version := "0.1.0"

scalaVersion := "2.11.7"

mainClass in assembly := Some("edu.illinois.ncsa.fence.Server")

lazy val versions = new {
  val finatra = "2.1.2"
  val guice = "4.0"
  val logback = "1.0.13"
}

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots"),
  "Twitter Maven" at "https://maven.twttr.com"
)


libraryDependencies ++= Seq(
  "com.twitter" %% "finagle-core" % "6.31.0",
  "com.twitter" %% "finagle-http" % "6.31.0",
  "com.twitter" %% "finagle-stats" % "6.31.0",
  "com.twitter" %% "finagle-redis" % "6.31.0",
  "com.twitter" %% "twitter-server" % "1.16.0",
  "com.typesafe" % "config" % "1.3.0",

  "com.twitter.finatra" %% "finatra-http" % versions.finatra % "provided",
  "com.twitter.finatra" %% "finatra-httpclient" % versions.finatra % "provided",
  "com.twitter.finatra" %% "finatra-slf4j" % versions.finatra % "provided",
  "com.twitter.inject" %% "inject-core" % versions.finatra % "provided",
  "ch.qos.logback" % "logback-classic" % versions.logback % "provided",

  "com.twitter.finatra" %% "finatra-http" % versions.finatra % "test",
  "com.twitter.finatra" %% "finatra-jackson" % versions.finatra % "test",
  "com.twitter.inject" %% "inject-server" % versions.finatra % "test",
  "com.twitter.inject" %% "inject-app" % versions.finatra % "test",
  "com.twitter.inject" %% "inject-core" % versions.finatra % "test",
  "com.twitter.inject" %% "inject-modules" % versions.finatra % "test",
  "com.google.inject.extensions" % "guice-testlib" % versions.guice % "test",

  "com.twitter.finatra" %% "finatra-http" % versions.finatra % "test" classifier "tests",
  "com.twitter.finatra" %% "finatra-jackson" % versions.finatra % "test" classifier "tests",
  "com.twitter.inject" %% "inject-app" % versions.finatra % "test" classifier "tests",
  "com.twitter.inject" %% "inject-core" % versions.finatra % "test" classifier "tests",
  "com.twitter.inject" %% "inject-modules" % versions.finatra % "test" classifier "tests",
  "com.twitter.inject" %% "inject-server" % versions.finatra % "test" classifier "tests",


  "org.mockito" % "mockito-core" % "1.9.5" % "test",
  "org.scalatest" %% "scalatest" % "2.2.3" % "test",
  "org.specs2" %% "specs2" % "2.3.12" % "test"


  //  "com.github.finagle" %% "finch-core" % "0.9.2",
//  "com.github.finagle" %% "finch-circe" % "0.9.2",
//  "io.circe" %% "circe-generic" % "0.2.1"
)

