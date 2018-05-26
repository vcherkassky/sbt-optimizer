val projectName = "sbt-optimizer"
val sbtScalariform = Project(projectName, file("."))
           sbtPlugin := true
        organization := "net.virtual-void"
                name := projectName
 sonatypeProfileName := organization.value
version in ThisBuild := "0.1.3"

  licenses := Seq(("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0")))
  homepage := scmInfo.value map (_.browseUrl)
  scmInfo :=
    Some(
      ScmInfo(url("http://github.com/jrudolph/sbt-optimizer"),
        "scm:git:git@github.com:jrudolph/sbt-optimizer.git")
    )
  developers := List(
    Developer(
      "jrudolph", "Johannes Rudolph", "johannes.rudolph at gmail com",
      url("http://virtual-void.net")
    )
  )

crossSbtVersions := Vector("1.1.5")

scalacOptions ++= List(
  "-unchecked",
  "-deprecation",
  "-Xlint",
  "-language:_",
  "-encoding", "UTF-8"
) ++ (if (scalaVersion.value startsWith "2.10.") List("-target:jvm-1.6") else List.empty)


com.typesafe.sbt.SbtScalariform.ScalariformKeys.preferences := {
  import scalariform.formatter.preferences._
  FormattingPreferences()
    .setPreference(RewriteArrowSymbols, true)
    .setPreference(AlignParameters, true)
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(DoubleIndentConstructorArguments, true)
}

publishMavenStyle := true
publishArtifact in Test := false
publishArtifact in (Compile, packageDoc) := true
publishArtifact in (Compile, packageSrc) := true
pomIncludeRepository := { _ => false }
pomExtra := Helpers.generatePomExtra(
  "git@github.com:jrudolph/sbt-optimizer.git",
  "scm:git:git@github.com:jrudolph/sbt-optimizer.git",
  "jrudolph", "Johannes Rudolph"
)
publishTo := getPublishToRepo.value
credentials ++= {
  val creds = Path.userHome / ".m2" / "credentials"
  if (creds.exists) Seq(Credentials(creds)) else Nil
}

def getPublishToRepo = Def.setting {
  if (isSnapshot.value)
    Some(Opts.resolver.sonatypeSnapshots)
  else
    Some(Opts.resolver.sonatypeStaging)
}
useGpg := true