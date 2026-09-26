package com.mesta.asset.ontology

import java.nio.file.Path

/** Whether a TypeQL type is an `entity` or a `relation`. */
enum class TqlKind { ENTITY, RELATION }

/**
 * One TypeQL `attribute` declaration.
 *
 * @property valueType the declared value type: `string`, `decimal`, `integer`, `double`, `date`, `datetime`.
 * @property values the `@values(...)` enumeration, empty when the attribute is not enumerated.
 * @property regex the `@regex(...)` pattern, null when absent.
 * @property rangeMin inclusive lower bound from `@range(a..b)`, null when absent.
 * @property rangeMax inclusive upper bound from `@range(a..b)`, null when absent.
 */
data class TqlAttribute(
    val name: String,
    val valueType: String,
    val values: List<String> = emptyList(),
    val regex: String? = null,
    val rangeMin: String? = null,
    val rangeMax: String? = null,
)

/** One `owns <attribute>` clause. [key] is true for `owns X @key`. */
data class TqlOwn(
    val attribute: String,
    val key: Boolean,
)

/** One `relates <role> @card(...)` clause. [minCount] is the lower bound of the cardinality. */
data class TqlRelate(
    val role: String,
    val minCount: Int,
)

/**
 * One TypeQL `entity` or `relation` declaration.
 *
 * @property players role name -> types that declare `plays <thisType>:<role>` anywhere in the
 *   schema. TypeQL allows the `plays` clause to live on the player, and to be spread across
 *   several statements.
 */
data class TqlType(
    val name: String,
    val kind: TqlKind,
    val superType: String?,
    val isAbstract: Boolean,
    val owns: List<TqlOwn>,
    val relates: List<TqlRelate>,
    val players: Map<String, Set<String>>,
)

/**
 * The subset of `ontology/mesta-investment.tql` that the OWL and SHACL artifacts must agree with.
 *
 * The parser is deliberately narrow: it understands the declaration forms actually used in the
 * schema file. A form it does not understand is a parse failure, not a silent skip.
 */
data class TypeQlSchema(
    val attributes: Map<String, TqlAttribute>,
    val types: Map<String, TqlType>,
) {
    val entities: Map<String, TqlType> get() = types.filterValues { it.kind == TqlKind.ENTITY }
    val relations: Map<String, TqlType> get() = types.filterValues { it.kind == TqlKind.RELATION }

    companion object {
        private val COMMENT = Regex("(?m)#.*$")
        private val CARD = Regex("""@card\(\s*(\d+)(?:\.\.(\d*))?\s*\)""")
        private val REGEX_ANNOTATION = Regex("""@regex\(\s*"((?:[^"\\]|\\.)*)"\s*\)""")
        private val VALUES_ANNOTATION = Regex("""@values\(\s*(.*?)\s*\)""")
        private val RANGE_ANNOTATION = Regex("""@range\(\s*([\d.]+)\s*\.\.\s*([\d.]+)\s*\)""")
        private val VALUE_TYPE = Regex("""\bvalue\s+(\w+)""")
        private val QUOTED = Regex(""""(?:[^"\\]|\\.)*"""")

        /** Parse a TypeQL schema file. */
        fun parse(path: Path): TypeQlSchema = parse(path.toFile().readText())

        /** Parse TypeQL schema text. */
        fun parse(text: String): TypeQlSchema {
            val body = text.replace(COMMENT, "").replaceFirst(Regex("""\bdefine\b"""), "")
            val attributes = linkedMapOf<String, TqlAttribute>()
            val types = linkedMapOf<String, MutableType>()
            val plays = mutableListOf<Triple<String, String, String>>()

            for (raw in body.split(";")) {
                val statement = raw.trim()
                if (statement.isEmpty()) continue
                when {
                    statement.startsWith("attribute ") -> {
                        val attribute = parseAttribute(statement)
                        attributes[attribute.name] = attribute
                    }
                    statement.startsWith("entity ") || statement.startsWith("relation ") -> {
                        val kind = if (statement.startsWith("entity ")) TqlKind.ENTITY else TqlKind.RELATION
                        val keyword = if (kind == TqlKind.ENTITY) "entity " else "relation "
                        val parts = statement.removePrefix(keyword).split(",").map { it.trim() }
                        val head = parts.first()
                        val name = head.substringBefore(' ').trim()
                        val isAbstract = head.contains("@abstract")
                        val entry = types.getOrPut(name) { MutableType(name, kind) }
                        if (isAbstract) entry.isAbstract = true
                        for (part in parts.drop(1)) {
                            when {
                                part.startsWith("sub ") -> entry.superType = part.removePrefix("sub ").trim()
                                part.startsWith("owns ") -> entry.owns += parseOwn(part.removePrefix("owns ").trim())
                                part.startsWith("relates ") -> entry.relates += parseRelate(part.removePrefix("relates ").trim())
                                part.startsWith("plays ") -> {
                                    val (relation, role) = splitPlay(part.removePrefix("plays ").trim())
                                    plays += Triple(relation, role, name)
                                }
                            }
                        }
                    }
                    else -> error("Unrecognised TypeQL statement: $statement")
                }
            }

            val playersByRelationRole = mutableMapOf<Pair<String, String>, MutableSet<String>>()
            for ((relation, role, player) in plays) {
                playersByRelationRole.getOrPut(relation to role) { linkedSetOf() } += player
            }

            return TypeQlSchema(
                attributes = attributes,
                types =
                    types.mapValues { (name, entry) ->
                        TqlType(
                            name = name,
                            kind = entry.kind,
                            superType = entry.superType,
                            isAbstract = entry.isAbstract,
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

        private fun parseAttribute(statement: String): TqlAttribute {
            val body = statement.removePrefix("attribute ")
            val name = body.substringBefore(',').trim()
            val valueType =
                VALUE_TYPE.find(body)?.groupValues?.get(1)
                    ?: error("attribute $name has no 'value <type>'")
            val values =
                VALUES_ANNOTATION
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                    ?.let { QUOTED.findAll(it).map { m -> m.value.removeSurrounding("\"") }.toList() }
                    ?: emptyList()
            val range = RANGE_ANNOTATION.find(body)
            return TqlAttribute(
                name = name,
                valueType = valueType,
                values = values,
                regex = REGEX_ANNOTATION.find(body)?.groupValues?.get(1),
                rangeMin = range?.groupValues?.get(1),
                rangeMax = range?.groupValues?.get(2),
            )
        }

        private fun parseOwn(clause: String): TqlOwn = TqlOwn(attribute = clause.substringBefore(' ').trim(), key = clause.contains("@key"))

        private fun parseRelate(clause: String): TqlRelate {
            val role = clause.substringBefore(' ').trim()
            val minCount =
                CARD
                    .find(clause)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt() ?: 0
            return TqlRelate(role = role, minCount = minCount)
        }

        private fun splitPlay(clause: String): Pair<String, String> {
            val relation = clause.substringBefore(':').trim()
            val role = clause.substringAfter(':').trim()
            check(relation.isNotEmpty() && role.isNotEmpty()) { "malformed plays clause: $clause" }
            return relation to role
        }

        private class MutableType(
            val name: String,
            val kind: TqlKind,
        ) {
            var superType: String? = null
            var isAbstract: Boolean = false
            val owns = mutableListOf<TqlOwn>()
            val relates = mutableListOf<TqlRelate>()
        }
    }
}

/** `legal-name` -> `legalName`. */
fun kebabToCamel(name: String): String =
    name.split('-').let { it.first() + it.drop(1).joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) } }

/** `ledger-event` -> `LedgerEvent`. */
fun kebabToPascal(name: String): String = name.split('-').joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }
