package rocks.muki.graphql

import rocks.muki.graphql.releasenotes.MarkdownReleaseNotes
import rocks.muki.graphql.schema.SchemaLoader
import sangria.schema._
import sbt._
import sbt.Keys._

object GraphQLSchemaPlugin extends AutoPlugin {

  override val requires: Plugins = GraphQLPlugin

  // The main class for the schema generator class
  private val mainClass = "SchemaGen"
  // The package for the schema generated class
  private val packageName = "graphql"

  object autoImport {

    /**
      * A scala snippet that returns the [[sangria.schema.Schema]] for your graphql application.
      *
      * @example if your schema is defined on an object
      * {{{
      *   graphqlSchemaSnippet := "com.example.MySchema.schema"
      * }}}
      */
    val graphqlSchemaSnippet: SettingKey[String] =
      settingKey[String]("code snippet that returns the sangria Schema")

    /**
      * Generates a the graphql schema based on the code snippet provided via `graphqlSchemaSnippet`
      */
    val graphqlSchemaGen: TaskKey[File] =
      taskKey[File]("generates a graphql schema file")

    /**
      * Returns the changes between the two schemas defined as parameters.
      *
      * `graphqlSchemaChanges <new schema> <old schema>`
      *
      * @example compare two schemas
      * {{{
      * $ sbt
      * > graphqlSchemaChanges build prod
      * }}}
      *
      */
    val graphqlSchemaChanges: InputKey[Vector[SchemaChange]] =
      inputKey[Vector[SchemaChange]]("compares two schemas")

    /**
      * Validates the new schema against existing queries and the production schema
      */
    val graphqlValidateSchema: InputKey[Unit] = inputKey[Unit](
      "Validates the new schema against existing queries and the production schema")

    /**
      * Creates release notes for changes between the the two given schemas
      */
    val graphqlReleaseNotes: InputKey[String] = inputKey[String](
      "Creates release notes for changes between the the two given schemas")

  }
  import autoImport._
  import GraphQLPlugin.autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(
    graphqlSchemaSnippet := """sys.error("Configure the `graphqlSchemaSnippet` setting with the correct scala code snippet to access your sangria schema")""",
    graphqlSchemaChanges := graphqlSchemaChangesTask.evaluated,
    target in graphqlSchemaGen := (target in Compile).value / "sbt-graphql",
    graphqlSchemaGen := {
      val schemaFile = (target in graphqlSchemaGen).value / "schema.graphql"
      runner.value.run(
        s"$packageName.$mainClass",
        Attributed.data((fullClasspath in Compile).value),
        List(schemaFile.getAbsolutePath),
        streams.value.log
      )
      streams.value.log.info(s"Generating schema in $schemaFile")
      schemaFile
    },
    // add the schema produced by this build to the graphqlSchemas
    (name in graphqlSchemaGen) := "build",
    graphqlSchemas += schema.GraphQLSchema(
      (name in graphqlSchemaGen).value,
      "schema generated by this build (graphqlSchemaGen task)",
      graphqlSchemaGen.map(SchemaLoader.fromFile(_).loadSchema()).taskValue
    ),
    graphqlValidateSchema := graphqlValidateSchemaTask.evaluated,
    graphqlReleaseNotes := (new MarkdownReleaseNotes)
      .generateReleaseNotes(graphqlSchemaChanges.evaluated),
    // Generates a small snippet that generates a graphql schema
    sourceGenerators in Compile += generateSchemaGeneratorClass()
  )

  /**
    * Generates a small code snippet that accessres the schema definition in the original
    * code base and renders it as a graphql schema definition file.
    *
    * @see https://github.com/mediative/sangria-codegen/blob/master/sbt-sangria-codegen/src/main/scala/com.mediative.sangria.codegen.sbt/SangriaSchemagenPlugin.scala#L121-L153
    * @return
    */
  private def generateSchemaGeneratorClass() = Def.task {
    val schemaCode = graphqlSchemaSnippet.value
    val file = sourceManaged.value / "sbt-sangria-codegen" / s"$mainClass.scala"

    val content = s"""|package $packageName
                      |object $mainClass {
                      |  val schema: sangria.schema.Schema[_, _] = {
                      |    $schemaCode
                      |  }
                      |  def main(args: Array[String]): Unit = {
                      |    val schemaFile = new java.io.File(args(0))
                      |    val graphql: String = schema.renderPretty
                      |    schemaFile.getParentFile.mkdirs()
                      |    new java.io.PrintWriter(schemaFile) {
                      |      write(graphql)
                      |      close()
                      |    }
                      |    ()
                      |  }
                      |}
      """.stripMargin

    IO.write(file, content)
    Seq(file)
  }

  private val graphqlSchemaChangesTask = Def.inputTaskDyn {
    val log = streams.value.log
    val (oldSchemaDefinition, newSchemaDefinition) =
      tupleGraphQLSchemaParser.parsed

    Def.task {
      val newSchema = newSchemaDefinition.schemaTask.value
      val oldSchema = oldSchemaDefinition.schemaTask.value
      log.info(
        s"Comparing ${oldSchemaDefinition.label} with ${newSchemaDefinition.label} schema")
      oldSchema compare newSchema
    }
  }

  private val graphqlValidateSchemaTask = Def.inputTask[Unit] {
    val log = streams.value.log
    val breakingChanges =
      graphqlSchemaChanges.evaluated.filter(_.breakingChange)
    if (breakingChanges.nonEmpty) {
      breakingChanges.foreach(change => log.error(s" * ${change.description}"))
      quietError("Validation failed: Breaking changes found")
    }
  }

}
