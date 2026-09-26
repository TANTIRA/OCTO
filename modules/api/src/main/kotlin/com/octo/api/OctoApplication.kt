package com.octo.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class OctoApplication

fun main(args: Array<String>) {
    runApplication<OctoApplication>(*args)
}
