package com.ff // <--- Asegúrate de que coincida con tu paquete

import io.ktor.server.application.*
import io.ktor.server.netty.*
import com.ff.config.configureDatabases // <--- Importamos la configuración de la DB
import com.ff.plugins.* // <--- Importamos Rutas y JSON

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureDatabases()
    configureSerialization() // <--- ¡ESTA LÍNEA ES VITAL!
    configureRouting()
}