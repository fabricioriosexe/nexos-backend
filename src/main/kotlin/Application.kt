package com.ff

import io.ktor.server.application.*
import io.ktor.server.netty.*
import com.ff.config.configureDatabases
import com.ff.plugins.* // Importar los plugins

fun main(args: Array<String>) {
    EngineMain.main(args)
}
fun Application.module() {
    configureDatabases()
    configureSerialization()

    // ⚠️ ¡IMPORTANTE! Esto debe ir ANTES de las rutas
    configureHTTP()

    // Las rutas van al final
    configureRouting()
}