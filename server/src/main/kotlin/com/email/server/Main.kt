package com.email.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val config = ServerConfig.fromEnvironment()
    when (args.firstOrNull()) {
        null, "serve" -> serve(config)
        "reset-admin-password" -> {
            // For when the break-glass password is lost: `same-server reset-admin-password [new]`.
            val services = Services.open(config)
            val (username, password) = runBlocking { services.resetBreakGlass(args.getOrNull(1)) }
            println("The break-glass admin $username now has the password: $password")
            services.close()
        }
        "version", "--version" -> println(Services.version)
        else -> {
            System.err.println("Usage: same-server [serve | reset-admin-password [password] | version]")
            exitProcess(2)
        }
    }
}

private fun serve(config: ServerConfig) {
    val log = LoggerFactory.getLogger("same")
    val services = Services.open(config)
    runBlocking { services.bootstrap() }
    services.startHousekeeping()
    log.info("SuperAwesomeMagicalEmail server {} listening on {}:{}, data in {}", Services.version, config.host, config.port, config.dataDir)
    val server = embeddedServer(Netty, port = config.port, host = config.host) { module(services) }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop(1_000, 5_000)
            services.close()
        },
    )
    server.start(wait = true)
}
