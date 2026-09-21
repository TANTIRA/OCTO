package com.mesta.asset.ontology

import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.RDFList
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.vocabulary.OWL
import org.apache.jena.vocabulary.RDF
import org.apache.jena.vocabulary.RDFS
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drift guard between `ontology/mesta-investment.tql` (TypeQL, the schema of record) and the OWL
 * plus SHACL artifacts.
 *
 * The mirror shape file must agree with the schema in both directions. Adding a type to the schema
 * without adding it to the mirror fails here, and so does weakening a mirror constraint that the
 * schema still requires. Business rules that deliberately exceed the schema live in
 * `mesta-investment-policy-shacl.ttl` and are not checked against it.
 */
class TypeQlSchemaDriftTest {
    private val dir: Path =
        Path.of(
            System.getProperty("ontology.dir")
                ?: error("ontology.dir system property is not set — see modules/ontology/build.gradle.kts"),
        )

    private val schema = TypeQlSchema.parse(dir.resolve("mesta-investment.tql"))
    private val owl = load(dir.resolve(OntologyValidator.ONTOLOGY_FILE))
    private val mirror = load(dir.resolve("mesta-investment-shacl.ttl"))

    @Test
    fun `every TypeQL attribute is an OWL datatype property and vice versa`() {
        val declared =
            owl
                .listSubjectsWithProperty(RDF.type, OWL.DatatypeProperty)
                .toList()
                .map { local(it.uri) }
                .toSet()
        assertEquals(
            schema.attributes.keys
                .map(::kebabToCamel)
                .toSet(),
            declared,
        )
    }

    @Test
    fun `every TypeQL entity and relation is an OWL class and vice versa`() {
        val declared =
            owl
                .listSubjectsWithProperty(RDF.type, OWL.Class)
                .toList()
                .map { local(it.uri) }
                .toSet()
        assertEquals(
            schema.types.keys
                .map(::kebabToPascal)
                .toSet(),
            declared,
        )
    }

    @Test
    fun `TypeQL subtyping is mirrored as rdfs subClassOf`() {
        val expected =
            schema.types.values
                .filter { it.superType != null }
                .associate { kebabToPascal(it.name) to setOf(kebabToPascal(it.superType!!)) }
        val actual =
            schema.types.keys
                .map(::kebabToPascal)
                .associateWith { classUri ->
                    owl
                        .getResource(INV + classUri)
                        .listProperties(RDFS.subClassOf)
                        .toList()
                        .map { local(it.`object`.asResource().uri) }
                        .toSet()
                }.filterValues { it.isNotEmpty() }
        assertEquals(expected, actual)
    }

    @Test
    fun `every TypeQL role is an OWL object property and vice versa`() {
        val declared =
            owl
                .listSubjectsWithProperty(RDF.type, OWL.ObjectProperty)
                .toList()
                .map { local(it.uri) }
                .toSet()
        val expected =
            schema.relations.values
                .flatMap { it.relates.map { relate -> kebabToCamel(relate.role) } }
                .toSet()
        assertEquals(expected, declared)
    }

    @Test
    fun `role rdfs range is a common supertype of every player, and absent when there is none`() {
        for (relation in schema.relations.values) {
            for (relate in relation.relates) {
                val players = relation.players[relate.role].orEmpty()
                if (players.isEmpty()) continue
                val common = players.map(::ancestorsOf).reduce { acc, next -> acc intersect next }
                val declared =
                    owl
                        .getResource(INV + kebabToCamel(relate.role))
                        .getProperty(RDFS.range)
                        ?.`object`
                        ?.asResource()
                        ?.let { local(it.uri) }
                val where = "${relation.name}:${relate.role}"
                if (common.isEmpty()) {
                    assertNull(declared, "$where has players with no common supertype, so rdfs:range must be absent")
                } else {
                    assertTrue(
                        declared != null && declared in common,
                        "$where range must be one of $common but was $declared",
                    )
                }
            }
        }
    }

    @Test
    fun `mirror shapes match the schema for every owned attribute and related role`() {
        val shapes = readShapes(mirror).associateBy { it.targetClass }
        val knownTypes =
            schema.types.keys
                .map(::kebabToPascal)
                .toSet()
        for (target in shapes.keys) {
            assertTrue(target in knownTypes, "mirror shape targets $target, which is not a TypeQL type")
        }

        for (type in schema.types.values) {
            val shape = shapes[kebabToPascal(type.name)]
            val byPath = shape?.props?.associateBy { it.path }.orEmpty()
            val allowed =
                (
                    type.owns.map { kebabToCamel(it.attribute) } +
                        type.relates.map { kebabToCamel(it.role) }
                ).toSet()
            shape?.props?.forEach {
                assertTrue(it.path in allowed, "${type.name} shape constrains ${it.path}, which the schema does not declare")
            }

            for (own in type.owns) {
                val attribute = schema.attributes.getValue(own.attribute)
                val prop =
                    byPath[kebabToCamel(own.attribute)]
                        ?: error("${type.name} owns ${own.attribute} but the mirror has no property shape for it")
                val where = "${type.name}.${own.attribute}"
                assertEquals(xsdLocal(attribute.valueType), prop.datatype, "$where datatype")
                assertEquals(if (own.key) 1 else null, prop.minCount, "$where minCount (only @key is required)")
                if (attribute.values.isEmpty()) {
                    assertNull(prop.inValues, "$where has sh:in but the schema declares no @values")
                } else {
                    assertEquals(attribute.values.toSet(), prop.inValues, "$where sh:in")
                }
                assertEquals(attribute.regex, prop.pattern, "$where sh:pattern")
                assertEquals(attribute.rangeMin, prop.minInclusive, "$where sh:minInclusive")
                assertEquals(attribute.rangeMax, prop.maxInclusive, "$where sh:maxInclusive")
            }

            for (relate in type.relates) {
                val prop = byPath[kebabToCamel(relate.role)]
                val where = "${type.name}.${relate.role}"
                if (relate.minCount >= 1) {
                    assertTrue(
                        prop != null && (prop.minCount ?: 0) >= 1,
                        "$where is @card(${relate.minCount}..) so the mirror needs sh:minCount",
                    )
                } else {
                    assertTrue(
                        prop == null || (prop.minCount ?: 0) == 0,
                        "$where is optional so the mirror must not require it",
                    )
                }
            }
        }
    }

    private fun load(path: Path): Model = ModelFactory.createDefaultModel().also { it.read(path.toUri().toString()) }

    private fun ancestorsOf(type: String): Set<String> {
        val result = linkedSetOf(kebabToPascal(type))
        var current = schema.types[type]?.superType
        while (current != null) {
            result += kebabToPascal(current)
            current = schema.types[current]?.superType
        }
        return result
    }

    private data class PropShape(
        val path: String,
        val datatype: String?,
        val minCount: Int?,
        val inValues: Set<String>?,
        val pattern: String?,
        val minInclusive: String?,
        val maxInclusive: String?,
    )

    private data class Shape(
        val targetClass: String,
        val props: List<PropShape>,
    )

    private fun readShapes(model: Model): List<Shape> {
        val nodeShape = ResourceFactory.createResource(SH + "NodeShape")
        return model.listSubjectsWithProperty(RDF.type, nodeShape).toList().map { node ->
            val target =
                node
                    .getProperty(sh("targetClass"))
                    ?.`object`
                    ?.asResource()
                    ?.let { local(it.uri) }
                    ?: error("NodeShape without sh:targetClass: $node")
            val props =
                node.listProperties(sh("property")).toList().map { statement ->
                    val blank = statement.`object`.asResource()
                    PropShape(
                        path =
                            blank
                                .getProperty(sh("path"))
                                ?.`object`
                                ?.asResource()
                                ?.let { local(it.uri) }
                                ?: error("property shape without sh:path"),
                        datatype =
                            blank
                                .getProperty(sh("datatype"))
                                ?.`object`
                                ?.asResource()
                                ?.let { local(it.uri) },
                        minCount =
                            blank
                                .getProperty(sh("minCount"))
                                ?.`object`
                                ?.asLiteral()
                                ?.int,
                        inValues =
                            blank
                                .getProperty(sh("in"))
                                ?.`object`
                                ?.`as`(RDFList::class.java)
                                ?.asJavaList()
                                ?.map { it.asLiteral().string }
                                ?.toSet(),
                        pattern =
                            blank
                                .getProperty(sh("pattern"))
                                ?.`object`
                                ?.asLiteral()
                                ?.string,
                        minInclusive =
                            blank
                                .getProperty(sh("minInclusive"))
                                ?.`object`
                                ?.asLiteral()
                                ?.lexicalForm,
                        maxInclusive =
                            blank
                                .getProperty(sh("maxInclusive"))
                                ?.`object`
                                ?.asLiteral()
                                ?.lexicalForm,
                    )
                }
            Shape(target, props)
        }
    }

    private companion object {
        const val INV = "https://mesta.asset/ontology/investment#"
        const val SH = "http://www.w3.org/ns/shacl#"

        fun sh(local: String) = ResourceFactory.createProperty(SH + local)

        fun local(uri: String): String = uri.substringAfterLast('#').substringAfterLast('/')

        /** TypeQL value type -> XSD local name. */
        fun xsdLocal(valueType: String): String =
            when (valueType) {
                "string" -> "string"
                "decimal" -> "decimal"
                "integer" -> "integer"
                "double" -> "double"
                "date" -> "date"
                "datetime" -> "dateTime"
                else -> error("unmapped TypeQL value type: $valueType")
            }
    }
}
