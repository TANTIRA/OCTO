package com.octo.api

import org.springframework.boot.actuate.autoconfigure.neo4j.Neo4jHealthContributorAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration
import org.springframework.boot.runApplication

// Boot's own Neo4j driver and health contributor stay off: the graph is wired by GraphConfiguration only when
// NEO4J_URI is set, and readiness must never depend on the derived graph (ADR-0004 amendment, #308).
@SpringBootApplication(exclude = [Neo4jAutoConfiguration::class, Neo4jHealthContributorAutoConfiguration::class])
class OctoApplication

fun main(args: Array<String>) {
    runApplication<OctoApplication>(*args)
}
