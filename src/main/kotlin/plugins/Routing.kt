package com.ff.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.ff.routes.userRoutes
import com.ff.routes.interviewRoutes // <--- Importar

fun Application.configureRouting() {
    routing {
        get("/") { call.respondText("Nexos API Running") }

        userRoutes()
        interviewRoutes() // <--- ¡ACTIVAR AQUI!
    }
}