import sbt.Keys.autoCompilerPlugins


//val dottyVersion = "3.4.0-RC1-bin-SNAPSHOT"
val dottyVersion = "3.3.6"
val dottyCpsAsyncVersion = "1.1.3-SNAPSHOT"

ThisBuild/version := "1.1.0"
ThisBuild/versionScheme := Some("semver-spec")
ThisBuild/organization := "io.github.dotty-cps-async"
ThisBuild/resolvers += Resolver.mavenLocal
ThisBuild/publishTo := localStaging.value

Global / concurrentRestrictions += Tags.limit(ScalaJSTags.Link, 1)
Global / concurrentRestrictions += Tags.limit(ScalaJSTags.Link, 1)

lazy val commonSettings = Seq(
   scalaVersion := dottyVersion,
   libraryDependencies += "io.github.dotty-cps-async" %%% "dotty-cps-async" % dottyCpsAsyncVersion,
   libraryDependencies += "org.scalameta" %%% "munit" % "1.0.4" % Test,
   testFrameworks += new TestFramework("munit.Framework"),
   scalacOptions ++= Seq( "-Wvalue-discard", "-Wnonunit-statement"),
   autoCompilerPlugins := true,
   addCompilerPlugin(("io.github.dotty-cps-async" % "dotty-cps-async-compiler-plugin" % dottyCpsAsyncVersion).cross(CrossVersion.full))
)


lazy val scalaz  = crossProject(JSPlatform, JVMPlatform)
  .in(file("scalaz"))
  .settings(
    commonSettings,
    name := "cps-async-connect-scalaz",
    libraryDependencies += "org.scalaz" %%% "scalaz-effect" % "7.4.0-M14" ,
    libraryDependencies += "org.scalaz" %%% "scalaz-core" % "7.4.0-M14" 
  ).jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
    scalaJSUseMainModuleInitializer := true
  )


lazy val catsEffect  = crossProject(JSPlatform, JVMPlatform)
  .in(file("cats-effect"))
  .settings(
    commonSettings,
    name := "cps-async-connect-cats-effect",
    libraryDependencies += "org.typelevel" %%% "cats-effect" % "3.6.1",
    libraryDependencies += "org.typelevel" %%% "munit-cats-effect" % "2.1.0" % Test
  ).jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
    scalaJSUseMainModuleInitializer := true,
  ).jvmSettings(
    scalacOptions ++= Seq( "-unchecked", "-explain")
  )

lazy val catsEffectLoom = project.in(file("cats-effect-loom"))
                                 .dependsOn(catsEffect.jvm)
                                 .settings(
                                     commonSettings,
                                     name := "cps-async-connect-cats-effect-loom",
                                     libraryDependencies ++= Seq(
                                       "io.github.dotty-cps-async" %% "dotty-cps-async-loom" % dottyCpsAsyncVersion,
                                       "org.typelevel" %%% "munit-cats-effect" % "2.1.0" % Test
                                     ),
                                     scalacOptions += "-Xtarget:21"
                                 )


lazy val monix  = crossProject(JSPlatform, JVMPlatform)
  .in(file("monix"))
  .settings(
    commonSettings,
    name := "cps-async-connect-monix",
    libraryDependencies += "io.monix" %%% "monix" % "3.4.1",
  ).jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
    scalaJSUseMainModuleInitializer := true
  ).jvmSettings(
  )

lazy val zio  = crossProject(JSPlatform, JVMPlatform)   
  .in(file("zio"))
  .settings(
    commonSettings,
    name := "cps-async-connect-zio",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio" % "1.0.18",
      "dev.zio" %%% "zio-streams" % "1.0.18",
    )
  ).jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
    //scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "scala-java-time" % "2.6.0",
      "io.github.cquiroz" %%% "scala-java-time-tzdb" % "2.6.0"
    ),
  ).jvmSettings(
    scalacOptions ++= Seq( "-unchecked", "-Ydebug-trace", "-Ydebug-names", "-Xprint-types",
                            "-Ydebug", "-uniqid", "-Ycheck:macros",  "-Yprint-syms" )
  )

lazy val zio2  = crossProject(JSPlatform,JVMPlatform) 
  .in(file("zio2"))
  .settings(
    commonSettings,
    name := "cps-async-connect-zio2",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio" % "2.1.19",
      "dev.zio" %%% "zio-managed" % "2.1.19",
      "dev.zio" %%% "zio-streams" % "2.1.19",
    )
  ).jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) },
    //scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "scala-java-time" % "2.6.0",
      "io.github.cquiroz" %%% "scala-java-time-tzdb" % "2.6.0"
    ),
  ).jvmSettings(
    scalacOptions ++= Seq( "-unchecked", "-Ydebug-trace", "-Ydebug-names", "-Xprint-types",
                            "-Ydebug", "-uniqid", "-Ycheck:macros",  "-Yprint-syms" 
                           )
  )

lazy val zio2Loom = project.in(file("zio2-loom"))
  .dependsOn(zio2.jvm)
  .settings(
    commonSettings,
    name := "cps-async-connect-zio2-loom",
    libraryDependencies ++= Seq(
      "io.github.dotty-cps-async" %% "dotty-cps-async-loom" % dottyCpsAsyncVersion
    ),
    scalacOptions += "-Xtarget:21"
  )


lazy val streamFs2 = crossProject(JSPlatform, JVMPlatform)
                     .in(file("stream-fs2"))
                     .dependsOn(catsEffect)
                     .settings(
                         commonSettings,
                         name := "cps-async-connect-fs2",
                         libraryDependencies ++= Seq(
                             "co.fs2" %%% "fs2-core" % "3.12.0",
                             "org.typelevel" %%% "munit-cats-effect" % "2.1.0" % Test
                         )
                      )

lazy val streamAkka = (project in file("stream-akka")).
                      settings(
                         commonSettings,
                         name := "cps-async-connect-akka-stream",
                         scalacOptions += "-explain",
                         resolvers += "Akka library repository".at("https://repo.akka.io/maven"),
                         libraryDependencies ++= Seq(
                            ("com.typesafe.akka" %% "akka-stream" % "2.10.6")
                         )
                      )

lazy val streamPekko = (project in file("stream-pekko")).
  settings(
    commonSettings,
    name := "cps-async-connect-pekko-stream",
    scalacOptions += "-explain",
    libraryDependencies ++= Seq(
      ("org.apache.pekko" %% "pekko-stream" % "1.1.3")
    )
  )


lazy val probabilityMonad = (project in file("probability-monad")).
                             settings(
                               commonSettings,
                               name := "cps-async-connect-probabiliy-monad",
                               libraryDependencies ++= Seq(
                                  ("org.jliszka" %%% "probability-monad" % "1.0.4").cross(CrossVersion.for3Use2_13)
                               )
                             )


lazy val cpsAsyncConnect = (project in file("."))
                .aggregate(catsEffect.jvm, catsEffect.js,
                           catsEffectLoom,
                           monix.jvm, monix.js,
                           scalaz.jvm, scalaz.js , 
                           zio.jvm,  zio.js,
                           zio2.jvm,  zio2.js, 
                           zio2Loom,
                           streamFs2.jvm, streamFs2.js,
                           streamAkka,
                           streamPekko,
                           probabilityMonad
                )


