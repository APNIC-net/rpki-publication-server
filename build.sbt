organization := "net.ripe"

version := "0.1"

scalaVersion := "2.11.6"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

enablePlugins(UniversalPlugin, JavaServerAppPackaging)

resolvers += "Codehaus Maven2 Repository" at "http://repository.codehaus.org/"

resolvers += "JCenter" at "http://jcenter.bintray.com/"

libraryDependencies ++= {
  val akkaV = "2.3.9"
  val sprayV = "1.3.3"
  Seq(
    "io.spray"                 %% "spray-can"         % sprayV,
    "io.spray"                 %% "spray-routing"     % sprayV,
    "io.spray"                 %% "spray-testkit"     % sprayV    % "test",
    "com.typesafe.akka"        %% "akka-actor"        % akkaV,
    "com.typesafe.akka"        %% "akka-testkit"      % akkaV     % "test",
    "org.scalatest"            %% "scalatest"         % "2.2.4"   % "test",
    "org.mockito"               % "mockito-all"       % "1.9.5"   % "test",
    "org.codehaus.woodstox"     % "woodstox-core-asl" % "4.4.1",
    "com.sun.xml.bind"          % "jaxb1-impl"        % "2.2.5.1",
    "org.slf4j"                 % "slf4j-api"         % "1.7.12",
    "org.slf4j"                 % "slf4j-log4j12"     % "1.7.12",
    "com.softwaremill.macwire" %% "macros"            % "1.0.1",
    "com.softwaremill.macwire" %% "runtime"           % "1.0.1",
    "io.spray"                 %% "spray-json"        % "1.3.2"
  )
}

Revolver.settings: Seq[sbt.Setting[_]]


// Package the initd script. Note: the Universal plugin will make anything in a bin/ directory executable.
mappings in Universal += file("src/main/scripts/rpki-publication-server.sh") -> "bin/rpki-publication-server.sh"

// Package the build.properties file
//mappings in Universal += file("build.properties") -> "build.properties"

