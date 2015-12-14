name := """animal-kingdom"""

version := "0.1.0"

scalaVersion := "2.11.7"

resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies ++= Seq(
  "com.twitter" %% "finagle-core" % "6.31.0",
  "com.twitter" %% "finagle-http" % "6.31.0",
  "com.twitter" %% "finagle-stats" % "6.31.0",
  "com.twitter" %% "twitter-server" % "1.16.0",
  "com.typesafe" % "config" % "1.3.0"
//  "com.github.finagle" %% "finch-core" % "0.9.2",
//  "com.github.finagle" %% "finch-circe" % "0.9.2",
//  "io.circe" %% "circe-generic" % "0.2.1"
)

