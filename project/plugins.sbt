/*resolvers += Resolver.url(
  "lila-maven-sbt",
  url("https://schlawg.org:30080")
)(Resolver.ivyStylePatterns)

ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
*/
addSbtPlugin("com.typesafe.play" % "sbt-plugin"   % "2.8.16-lila_1.14.1-SNAPSHOT")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt" % "2.4.6")
addSbtPlugin("ch.epfl.scala"     % "sbt-bloop"    % "1.5.4")
