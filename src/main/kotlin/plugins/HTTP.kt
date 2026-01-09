package com.ff.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureHTTP() {
    install(CORS) {
        anyHost() // 🔓 Permite cualquier origen (localhost:5173, etc.)

        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        // 👇 ESTA FALTABA (Sin esto, el guardar perfil falla)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete) // Agreguemos Delete por si acaso
        allowMethod(HttpMethod.Patch)

        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        // allowCredentials = true // ⚠️ OJO: Con anyHost() a veces esto da conflicto, si falla coméntalo.
    }
}