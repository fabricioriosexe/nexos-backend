package com.ff.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.ff.routes.userRoutes
import com.ff.routes.interviewRoutes
import com.ff.routes.webhookRoutes // <--- IMPORTANTE: Asegurate de importar esto

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Nexos API Running")
        }
        userRoutes()
        interviewRoutes()
        webhookRoutes() // <--- ACTIVAR EL BUZÓN
    }
}