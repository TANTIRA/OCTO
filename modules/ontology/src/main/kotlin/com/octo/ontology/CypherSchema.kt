package com.octo.ontology

import java.nio.file.Path

/** Whether a schema type is an `entity` (node label) or a `relation` (edge or reified node). */
enum class CqlKind { ENTITY, RELATION }

/**
 * One attribute declaration carried in a `// type: attribute` comment block.
 *
 * @property valueType the declared value type: `string`, `decimal`, `integer`, `double`, `date`, `datetime`.
 * @property values the `@values(...)` enumeration, empty when the attribute is not enumerated.
 * @property regex the `@regex(...)` pattern, null when absent.
 * @property rangeMin inclusive lower bound from `@range(a..b)`, null when absent.
 * @property rangeMax inclusive upper bound from `@range(a..b)`, null when absent.
 */
data class CqlAttribute(
    val name: String,
    val valueType: String,
    val values: List<String> = emptyList(),
    val regex: String? = null,
    val rangeMin: String? = null,
    val rangeMax: String? = null,
)

/** One `owns <attribute>` clause. [key] is true for `owns X @key`, [unique] for `@unique`. */
data class CqlOwn(
    val attribute: String,
    val key: Boolean,
    val unique: Boolean,
)

/** One `relates <role> @card(...)` clause. [minCount] is the lower bound of the cardinality. */
data class CqlRelate(
    val role: String,
    val minCount: Int,
)

/**
 * One `entity` or `relation` declaration from `ontology/octo-investment.cypher`.
 *
 * @property players role name -> types that declare `plays <thisType>:<role>` anywhere in the
 *   schema, mirroring the TypeQL player's-clause spread across several blocks.
 * @property reified true when an n-ary relation is stored as a node (`reified: node`).
 */
data class CqlType(
    val name: String,
    val kind: CqlKind,
    val superType: String?,
    val isAbstract: Boolean,
    val reified: Boolean,
    val owns: List<CqlOwn>,
    val relates: List<CqlRelate>,
    val players: Map<String, Set<String>>,
)

/**
 * The subset of `ontology/octo-investment.cypher` that the OWL and SHACL artifacts must agree
 * with — parsed from the structured `// type:` comment blocks, which are the schema of record.
 * The `CREATE CONSTRAINT` statements between blocks are derived data and ignored here.
 *
 * The parser is deliberately narrow: it understands the dual-format convention documented at
 * the top of the schema file. A `// type:` block it cannot read is a parse failure, not a skip.
 */
data class CypherSchema(
    val attributes: Map<String, CqlAttribute>,
    val types: Map<String, CqlType>,
) {
    val entities: Map<String, CqlType> get() = types.filterValues { it.kind == CqlKind.ENTITY }
    val relations: Map<String, CqlType> get() = types.filterValues { it.kind == CqlKind.RELATION }

    companion object {
        private val CARD = Regex("""@card\(\s*(\d+)(?:\.\.(\d*))?\s*\)""")
        private val REGEX_ANNOTATION = Regex("""@regex\(\s*"((?:[^"\\]|\\.)*)"\s*\)""")
        private val VALUES_ANNOTATION = Regex("""@values\(\s*(.*?)\s*\)""")
        private val RANGE_ANNOTATION = Regex("""@range\(\s*([\d.]+)\s*\.\.\s*([\d.]+)\s*\)""")
        private val QUOTED = Regex(""""(?:[^"\\]|\\.)*"""")

        /** Parse the canonical Cypher schema file. */
        fun parse(path: Path): CypherSchema = parse(path.toFile().readText())

        /** Parse canonical Cypher schema text. */
        fun parse(text: String): CypherSchema {
            val attributes = linkedMapOf<String, CqlAttribute>()
            val types = linkedMapOf<String, MutableType>()
            val plays = mutableListOf<Triple<String, String, String>>()

            var current: MutableType? = null
            for (rawLine in text.lines()) {
                val line = rawLine.trim()
                if (!line.startsWith("//")) {
                    current = null
                    continue
                }
                val body = line.removePrefix("//").trim()
                val segments = body.split("|").map { it.trim() }
                when {
                    body.startsWith("type: attribute") -> {
                        val attribute = parseAttribute(segments.drop(1))
                        attributes[attribute.name] = attribute
                        current = null
                    }
                    body.startsWith("type: entity") || body.startsWith("type: relation") -> {
                        val kind = if (body.startsWith("type: entity")) CqlKind.ENTITY else CqlKind.RELATION
                        val entry = MutableType(kind = kind)
                        for (segment in segments.drop(1)) {
                            when {
                                segment.startsWith("name:") -> entry.name = segment.removePrefix("name:").trim()
                                segment.startsWith("sub:") ->
                                    segment
                                        .removePrefix("sub:")
                                        .trim()
                                        .takeIf { it != "-" && it != "none" }
                                        ?.let { entry.superType = it }
                                segment == "abstract: true" -> entry.isAbstract = true
                                segment == "reified: node" -> entry.reified = true
                            }
                        }
                        check(entry.name != null) { "type declaration without a name: $body" }
                        current = types.getOrPut(entry.name!!) { entry }
                    }
                    body.startsWith("owns:") -> {
                        val owner = current ?: error("owns list outside a type block: $body")
                        owner.owns += parseOwn(body.removePrefix("owns:"))
                    }
                    body.startsWith("relates:") -> {
                        val owner = current ?: error("relates list outside a type block: $body")
                        owner.relates += parseRelate(body.removePrefix("relates:"))
                    }
                    body.startsWith("plays:") -> {
                        val owner = current ?: error("plays list outside a type block: $body")
                        for (play in body
                            .removePrefix("plays:")
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }) {
                            val (relation, role) = splitPlay(play)
                            plays += Triple(relation, role, owner.name!!)
                        }
                    }
                    else -> current = null // free prose comment — ends the block
                }
            }

            val playersByRelationRole = mutableMapOf<Pair<String, String>, MutableSet<String>>()
            for ((relation, role, player) in plays) {
                playersByRelationRole.getOrPut(relation to role) { linkedSetOf() } += player
            }

            return CypherSchema(
                attributes = attributes,
                types =
                    types.mapValues { (name, entry) ->
                        CqlType(
                            name = name,
                            kind = entry.kind,
                            superType = entry.superType,
                            isAbstract = entry.isAbstract,
                            reified = entry.reified,
                            owns = entry.owns.toList(),
                            relates = entry.relates.toList(),
                            players =
                                playersByRelationRole
                                    .filterKeys { it.first == name }
                                    .mapKeys { it.key.second }
                                    .mapValues { it.value.toSet() },
                        )
                    },
            )
        }

        private fun parseAttribute(segments: List<String>): CqlAttribute {
            var name: String? = null
            var valueType: String? = null
            for (segment in segments) {
                when {
                    segment.startsWith("name:") -> name = segment.removePrefix("name:").trim()
                    segment.startsWith("value:") -> valueType = segment.removePrefix("value:").trim()
                }
            }
            requireNotNull(name) { "attribute without a name: $segments" }
            val joined = segments.joinToString(" | ")
            val values =
                VALUES_ANNOTATION
                    .find(joined)
                    ?.groupValues
                    ?.get(1)
                    ?.let { QUOTED.findAll(it).map { m -> m.value.removeSurrounding("\"") }.toList() }
                    ?: emptyList()
            val range = RANGE_ANNOTATION.find(joined)
            return CqlAttribute(
                name = name,
                valueType = requireNotNull(valueType) { "attribute $name has no 'value: <type>'" },
                values = values,
                regex = REGEX_ANNOTATION.find(joined)?.groupValues?.get(1),
                rangeMin = range?.groupValues?.get(1),
                rangeMax = range?.groupValues?.get(2),
            )
        }

        private fun parseOwn(list: String): List<CqlOwn> =
            list.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { clause ->
                CqlOwn(
                    attribute = clause.substringBefore(' ').trim(),
                    key = clause.contains("@key"),
                    unique = clause.contains("@unique"),
                )
            }

        private fun parseRelate(list: String): List<CqlRelate> =
            list.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { clause ->
                CqlRelate(
                    role = clause.substringBefore(' ').trim(),
                    minCount =
                        CARD
                            .find(clause)
                            ?.groupValues
                            ?.get(1)
                            ?.toInt() ?: 0,
                )
            }

        private fun splitPlay(clause: String): Pair<String, String> {
            val relation = clause.substringBefore(':').trim()
            val role = clause.substringAfter(':').trim()
            check(relation.isNotEmpty() && role.isNotEmpty()) { "malformed plays clause: $clause" }
            return relation to role
        }

        private class MutableType(
            val kind: CqlKind,
        ) {
            var name: String? = null
            var superType: String? = null
            var isAbstract: Boolean = false
            var reified: Boolean = false
            val owns = mutableListOf<CqlOwn>()
            val relates = mutableListOf<CqlRelate>()
        }
    }
}

/** `legal-name` -> `legalName`. */
fun kebabToCamel(name: String): String =
    name.split('-').let { it.first() + it.drop(1).joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) } }

/** `ledger-event` -> `LedgerEvent`. */
fun kebabToPascal(name: String): String = name.split('-').joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }
