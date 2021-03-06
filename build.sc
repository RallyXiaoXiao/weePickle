import $ivy.`com.typesafe::mima-reporter:0.3.0`
import mill._
import mill.modules._
import mill.scalajslib._
import mill.scalalib._
import mill.scalalib.publish._
import mill.scalajslib._
import com.typesafe.tools.mima.lib.MiMaLib
import com.typesafe.tools.mima.core._
import coursier.maven.MavenRepository
import mill.scalalib.scalafmt.ScalafmtModule
import mill.T
import mill.api.PathRef
import mill.scalalib.{JavaModule, PublishModule}
import os.Path
import mill.api.Ctx
import mill.scalalib.publish.Artifact
import os.Path


val scalaVersions = Seq("2.11.12", "2.12.12", "2.13.3")
val scalaPlayVersions = Seq(
  ("2.11.12", "2.5.19"),
  ("2.11.12", "2.7.4"),
  ("2.12.12", "2.7.4"),
  ("2.13.3", "2.7.4"),
  ("2.13.3", "2.8.1"),
  ("2.13.3", "2.9.0"),
)

trait CommonModule extends ScalaModule with ScalafmtModule {

  protected def shade(name: String) = name + "-v1"

  override def scalacOptions = T{
    val builder = Seq.newBuilder[String]
    builder ++= super.scalacOptions()
    builder ++= Seq(
      "-deprecation",
      "-language:higherKinds",
      "-language:implicitConversions",
      "-Xfatal-warnings",
      "-encoding", "utf8",
      "-feature"
    )

    if (!scalaVersion().startsWith("2.11")) {
      builder += "-opt:l:method"
    }

    if (scalaVersion().startsWith("2.13.")) {
      builder ++= Seq(
        // See: https://github.com/scala/scala/pull/8373
        """-Wconf:any:warning-verbose""",
        """-Wconf:cat=deprecation:info-summary""" // Not ready to deal with 2.13 collection deprecations.
      )
    }

    builder.result()
  }

  def platformSegment: String

  def isScalaOld = T{ scalaVersion() startsWith "2.11" }

  def sources = T.sources(
    millSourcePath / "src",
    millSourcePath / s"src-$platformSegment"
  )
}

trait CommonPublishModule extends CommonModule with PublishM2Module with CrossScalaModule{
  def publishVersion = "1.3.1-SNAPSHOT"

  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "com.rallyhealth",
    url = "https://github.com/rallyhealth/weePickle",
    licenses = Seq(License.MIT),
    scm = SCM(
      "git://github.com/rallyhealth/weePickle.git",
      "scm:git://github.com/rallyhealth/weePickle.git"
    ),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"),
      Developer("htmldoug", "Doug Roper", "https://github.com/htmldoug")
    )
  )
}

trait CommonTestModule extends CommonModule with TestModule{
  def ivyDeps = T{
    if (isScalaOld())
      Agg(ivy"com.lihaoyi::utest::0.6.8", ivy"com.lihaoyi::acyclic:0.1.9")
    else
      Agg(ivy"com.lihaoyi::utest::0.7.1", ivy"com.lihaoyi::acyclic:0.2.0")
  }
  def testFrameworks = Seq("utest.runner.Framework")

  override def test(args: String*) = T.command{
    if (isScalaOld()) ("", Nil)
    else super.test(args: _*)()
  }

  override def sources = T.sources(
    if (isScalaOld()) Nil
    else super.sources()
  )
}

trait CommonJvmModule extends CommonPublishModule with MiMa {
  def platformSegment = "jvm"
  def millSourcePath = super.millSourcePath / os.up
  trait Tests extends super.Tests with CommonTestModule{
    def platformSegment = "jvm"

    override def test(args: String*) = T.command{
      reportBinaryIssues()
      super.test(args: _*)()
    }
  }
}

object core extends Module {

  object jvm extends Cross[CoreJvmModule](scalaVersions: _*)
  class CoreJvmModule(val crossScalaVersion: String) extends CommonJvmModule {
    def artifactName = shade("weepickle-core")
    def ivyDeps = Agg(
      ivy"org.scala-lang.modules::scala-collection-compat:2.1.2"
    )

    object test extends Tests
  }
}


object implicits extends Module {

  trait ImplicitsModule extends CommonPublishModule{
    def compileIvyDeps = T{
      Agg(
        ivy"com.lihaoyi::acyclic:${if (isScalaOld()) "0.1.8" else "0.2.0"}",
        ivy"org.scala-lang:scala-reflect:${scalaVersion()}"
      )
    }
    def generatedSources = T{
      val dir = T.ctx().dest
      val file = dir / "weepickle" / "Generated.scala"
      ammonite.ops.mkdir(dir / "weepickle")
      val tuples = (1 to 22).map{ i =>
        def commaSeparated(s: Int => String) = (1 to i).map(s).mkString(", ")
        val writerTypes = commaSeparated(j => s"T$j: From")
        val readerTypes = commaSeparated(j => s"T$j: To")
        val typeTuple = commaSeparated(j => s"T$j")
        val implicitFromTuple = commaSeparated(j => s"implicitly[From[T$j]]")
        val implicitToTuple = commaSeparated(j => s"implicitly[To[T$j]]")
        val lookupTuple = commaSeparated(j => s"x(${j-1})")
        val fieldTuple = commaSeparated(j => s"x._$j")
        s"""
        implicit def Tuple${i}From[$writerTypes]: TupleNFrom[Tuple$i[$typeTuple]] =
          new TupleNFrom[Tuple$i[$typeTuple]](Array($implicitFromTuple), x => if (x == null) null else Array($fieldTuple))
        implicit def Tuple${i}To[$readerTypes]: TupleNTo[Tuple$i[$typeTuple]] =
          new TupleNTo(Array($implicitToTuple), x => Tuple$i($lookupTuple).asInstanceOf[Tuple$i[$typeTuple]])
        """
      }

      ammonite.ops.write(file, s"""
      package com.rallyhealth.weepickle.v1.implicits
      import acyclic.file
      import language.experimental.macros
      /**
       * Auto-generated picklers and unpicklers, used for creating the 22
       * versions of tuple-picklers and case-class picklers
       */
      trait Generated extends com.rallyhealth.weepickle.v1.core.Types{
        ${tuples.mkString("\n")}
      }
    """)
      Seq(PathRef(dir))
    }

  }

  object jvm extends Cross[JvmModule](scalaVersions: _*)
  class JvmModule(val crossScalaVersion: String) extends ImplicitsModule with CommonJvmModule{
    def moduleDeps = Seq(core.jvm())
    def artifactName = shade("weepickle-implicits")

    override def mimaBinaryIssueFilters = Seq[ProblemFilter](
      // Macros are used at compile time only:
      ProblemFilters.exclude[MissingClassProblem]("com.rallyhealth.weepickle.v1.implicits.internal.Macros*"),
      ProblemFilters.exclude[Problem]("com.rallyhealth.weepickle.v1.implicits.MacroImplicits#*"),
    )
    object test extends Tests {
      def moduleDeps = super.moduleDeps ++ Seq(weejson.jvm().test, core.jvm().test)
    }
  }
}


object weepack extends Module {

  object jvm extends Cross[JvmModule](scalaVersions: _*)
  class JvmModule(val crossScalaVersion: String) extends CommonJvmModule {
    def moduleDeps = Seq(core.jvm(), weepickle.jvm())
    def artifactName = shade("weepack")
    object test extends Tests with CommonModule  {
      def moduleDeps = super.moduleDeps ++ Seq(weejson.jvm().test, core.jvm().test)
    }
  }
}

object weejson extends Module{
  trait JsonModule extends CommonPublishModule{
    def artifactName = shade("weejson")
  }
  trait ScalaTestModule extends CommonTestModule{
    def ivyDeps = T{
      Agg(
        ivy"org.scalatest::scalatest::3.0.8",
        ivy"org.scalacheck::scalacheck::1.14.1"
      )
    }
    def testFrameworks = Seq("org.scalatest.tools.Framework")
  }

  object jvm extends Cross[JvmModule](scalaVersions: _*)
  class JvmModule(val crossScalaVersion: String) extends JsonModule with CommonJvmModule{
    def moduleDeps = Seq(core.jvm(), weejson.jackson())
    object test extends Tests with ScalaTestModule
  }

  object argonaut extends Cross[ArgonautModule](scalaVersions: _*)
  class ArgonautModule(val crossScalaVersion: String) extends CommonPublishModule{
    def artifactName = shade("weejson-argonaut")
    def platformSegment = "jvm"
    def moduleDeps = Seq(weejson.jvm())
    def ivyDeps = Agg(ivy"io.argonaut::argonaut:6.2.5")
  }

  object yaml extends Cross[YamlModule](scalaVersions: _*)
  class YamlModule(val crossScalaVersion: String) extends CommonPublishModule {
    def artifactName = shade("weeyaml")
    def platformSegment = "jvm"
    def moduleDeps = Seq(jackson())
    def ivyDeps = Agg(
      ivy"com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:${jacksonVersion}"
    )

    object test extends Tests with ScalaTestModule {
      def platformSegment = "jvm"

      override def moduleDeps = super.moduleDeps ++ Seq(weejson.jvm())
    }
  }

  object xml extends Cross[XmlModule](scalaVersions: _*)
  class XmlModule(val crossScalaVersion: String) extends CommonPublishModule {
    def artifactName = shade("weexml")
    def platformSegment = "jvm"
    def moduleDeps = Seq(jackson())
    def ivyDeps = Agg(
      ivy"com.fasterxml.jackson.dataformat:jackson-dataformat-xml:${jacksonVersion}"
    )

    object test extends Tests with ScalaTestModule {
      def platformSegment = "jvm"

      override def moduleDeps = super.moduleDeps ++ Seq(weejson.jvm())
    }
  }

  object json4s extends Cross[Json4sModule](scalaVersions: _*)
  class Json4sModule(val crossScalaVersion: String) extends CommonPublishModule{
    def artifactName = shade("weejson-json4s")
    def platformSegment = "jvm"
    def moduleDeps = Seq(weejson.jvm())
    def ivyDeps = Agg(
      ivy"org.json4s::json4s-ast:3.6.9",
      ivy"org.json4s::json4s-native:3.6.9"
    )
  }

  object circe extends Cross[CirceModule](scalaVersions: _*)
  class CirceModule(val crossScalaVersion: String) extends CommonPublishModule{
    def artifactName = shade("weejson-circe")
    def platformSegment = "jvm"
    def moduleDeps = Seq(weejson.jvm())
    def ivyDeps = T{
      Agg(ivy"io.circe::circe-parser:${if (isScalaOld()) "0.11.1" else "0.13.0"}")
    }
  }

  object play extends Cross[PlayModule](scalaPlayVersions:_*)
  class PlayModule(val crossScalaVersion: String, val crossPlayVersion: String) extends CommonPublishModule {

    override def millSourcePath = super.millSourcePath / os.up

    def artifactName = T {
      val name = "weejson-play" + crossPlayVersion.split('.').take(2).mkString // e.g. "25", "27"
      shade(name)
    }
    def platformSegment = "jvm"
    def moduleDeps = Seq(weepickle.jvm())
    def ivyDeps = T{
      Agg(
        ivy"com.typesafe.play::play-json:${crossPlayVersion}"
      )
    }

    object test extends Tests with CommonModule with ScalaTestModule {
      def moduleDeps = super.moduleDeps
      def platformSegment = "jvm"
    }
  }

  val jacksonVersion = "2.11.1"
  object jackson extends Cross[JacksonModule](scalaVersions:_*)
  class JacksonModule(val crossScalaVersion: String) extends CommonPublishModule {
    object test extends Tests with ScalaTestModule {
      def platformSegment = "jvm"
      def moduleDeps = Seq(weejson.jvm().test)
    }

    def artifactName = shade("weejson-jackson")
    def platformSegment = "jvm"
    def moduleDeps = Seq(core.jvm())
    def ivyDeps = T{
      Agg(
        ivy"com.fasterxml.jackson.core:jackson-core:${jacksonVersion}"
      )
    }
  }
}

trait weepickleModule extends CommonPublishModule{
  def artifactName = shade("weepickle")
  def compileIvyDeps = Agg(
    ivy"com.lihaoyi::acyclic:${if (isScalaOld()) "0.1.8" else "0.2.0"}",
    ivy"org.scala-lang:scala-reflect:${scalaVersion()}",
    ivy"org.scala-lang:scala-compiler:${scalaVersion()}"
  )
}

object weepickle extends Module{
  object jvm extends Cross[JvmModule](scalaVersions: _*)
  class JvmModule(val crossScalaVersion: String) extends weepickleModule with CommonJvmModule{
    def moduleDeps = Seq(weejson.jvm(), implicits.jvm(), weejson.jackson())

    object test extends Tests with CommonModule{
      def moduleDeps = {
        super.moduleDeps ++ Seq(
          weejson.yaml(),
          weejson.xml(),
          weejson.argonaut(),
          weejson.circe(),
          weejson.json4s(),
          weepack.jvm().test,
          weejson.play(),
          core.jvm().test
        )
      }
    }
  }
}

trait BenchModule extends CommonModule {
  def scalaVersion = "2.12.12"
  def millSourcePath = build.millSourcePath / "bench"
  def ivyDeps = Agg(
    ivy"io.circe::circe-core::0.13.0",
    ivy"io.circe::circe-generic::0.13.0",
    ivy"io.circe::circe-parser::0.13.0",
    ivy"com.typesafe.play::play-json::2.7.4",
    ivy"io.argonaut::argonaut:6.2.5",
    ivy"org.json4s::json4s-ast:3.6.9",
    ivy"com.lihaoyi::sourcecode::0.1.7",
  )
}

object bench extends Module {

  object jvm extends BenchModule with Jmh{
    def platformSegment = "jvm"
    def moduleDeps = Seq(weepickle.jvm("2.12.12").test)
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"com.fasterxml.jackson.module::jackson-module-scala:2.9.10",
      ivy"com.fasterxml.jackson.core:jackson-databind:2.9.4",
      ivy"com.lihaoyi::upickle:0.9.8",
      ivy"org.msgpack:jackson-dataformat-msgpack:0.8.20",
      ivy"com.fasterxml.jackson.dataformat:jackson-dataformat-smile:${weejson.jacksonVersion}",
    )
  }
}

trait MiMa extends ScalaModule with PublishModule {
  def previousVersions = T {
    Seq("1.3.0")
  }

  override def repositories = super.repositories ++ Seq(
    MavenRepository("https://dl.bintray.com/rallyhealth/maven")
  )

  def reportBinaryIssues = T {
    val msgs: Seq[String] = for {
      (prevArtifact, problems) <- mimaReportBinaryIssues()
        if problems.nonEmpty
    } yield {
      s"""Compared to artifact: ${prevArtifact}
         |found ${problems.size} binary incompatibilities:
         |${problems.mkString("\n")}""".stripMargin
    }

    if (msgs.nonEmpty) {
      sys.error(msgs.mkString("\n"))
    }
  }

  def mimaBinaryIssueFilters: Seq[ProblemFilter] = Seq.empty

  def previousDeps = T {
    Agg.from(previousVersions().map { version =>
      ivy"${pomSettings().organization}:${artifactId()}:${version}"
    })
  }

  def previousArtifacts = T {
    resolveDeps(previousDeps)().filter(_.path.segments.contains(artifactId()))
  }

  def mimaReportBinaryIssues: T[List[(String, List[String])]] = T {
    val currentClassfiles = compile().classes.path
    val classpath = runClasspath()

    val lib = {
      com.typesafe.tools.mima.core.Config.setup("sbt-mima-plugin", Array.empty)
      val cpstring = classpath
        .map(_.path)
        .filter(os.exists)
        .mkString(System.getProperty("path.separator"))
      new MiMaLib(
        com.typesafe.tools.mima.core.reporterClassPath(cpstring)
      )
    }

    previousArtifacts().toList.map { path =>
      val problems =
        lib.collectProblems(path.path.toString, currentClassfiles.toString)
      path.path.toString -> problems.filter { problem =>
        mimaBinaryIssueFilters.forall(_.apply(problem))
      }.map(_.description("current"))
    }
  }

}

trait Jmh extends ScalaModule {

  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"org.openjdk.jmh:jmh-core:1.21",
  )

  def runJmh(args: String*) = T.command {
    val (_, resources) = generateBenchmarkSources()
    Jvm.runSubprocess(
      "org.openjdk.jmh.Main",
      classPath = (runClasspath() ++ generatorDeps()).map(_.path) ++
        Seq(compileGeneratedSources().path, resources),
      mainArgs = args,
      workingDir = T.ctx.dest
    )
  }

  def compileGeneratedSources = T {
    val dest = T.ctx.dest
    val (sourcesDir, _) = generateBenchmarkSources()
    val sources = os.walk(sourcesDir).filter(os.isFile)
    os.proc("javac",
       sources.map(_.toString),
       "-cp",
       (runClasspath() ++ generatorDeps()).map(_.path.toString).mkString(":"),
       "-d",
       dest).call(dest)
    PathRef(dest)
  }

  // returns sources and resources directories
  def generateBenchmarkSources = T {
    val dest = T.ctx().dest

    val sourcesDir = dest / 'jmh_sources
    val resourcesDir = dest / 'jmh_resources

    os.remove.all(sourcesDir)
    os.makeDir.all(sourcesDir)
    os.remove.all(resourcesDir)
    os.makeDir.all(resourcesDir)

    Jvm.runSubprocess(
      "org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator",
      (runClasspath() ++ generatorDeps()).map(_.path),
      mainArgs = Array(
        compile().classes.path,
        sourcesDir,
        resourcesDir,
        "default"
      ).map(_.toString)
    )

    (sourcesDir, resourcesDir)
  }

  def generatorDeps = resolveDeps(
    T { Agg(ivy"org.openjdk.jmh:jmh-generator-bytecode:1.21") }
  )
}


trait PublishM2Module extends JavaModule with PublishModule {

  /**
    * Publish to the local Maven repository.
    * @param path The path to the local repository (default: `os.home / ".m2" / "repository"`).
    * @return [[PathRef]]s to published files.
    */
  def publishM2Local(path: Path = os.home / ".m2" / "repository") = T.command {
    new LocalM2Publisher(path)
      .publish(
        jar = jar().path,
        sourcesJar = sourceJar().path,
        docJar = docJar().path,
        pom = pom().path,
        artifact = artifactMetadata()
      ).map(PathRef(_))
  }

}

class LocalM2Publisher(m2Repo: Path) {

  def publish(
    jar: Path,
    sourcesJar: Path,
    docJar: Path,
    pom: Path,
    artifact: Artifact
  )(implicit ctx: Ctx.Log): Seq[Path] = {
    val releaseDir = m2Repo / artifact.group.split("[.]") / artifact.id / artifact.version
    ctx.log.info(s"Publish ${artifact.id}-${artifact.version} to ${releaseDir}")
    os.makeDir.all(releaseDir)
    Seq(
      jar -> releaseDir / s"${artifact.id}-${artifact.version}.jar",
      sourcesJar -> releaseDir / s"${artifact.id}-${artifact.version}-sources.jar",
      docJar -> releaseDir / s"${artifact.id}-${artifact.version}-javadoc.jar",
      pom -> releaseDir / s"${artifact.id}-${artifact.version}.pom"
    ).map {
      case (from, to) =>
        os.copy.over(from, to)
        to
    }
  }

}


