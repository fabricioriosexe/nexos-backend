package com.ff.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import com.ff.models.Users
// 👇 IMPORTANTE: Importamos todo lo que hay en dtos
import com.ff.dtos.* fun Route.userRoutes() {

    post("/users") {
        try {
            // 👇 CORRECCIÓN CLAVE: Agregamos <CreateUserRequest> entre los signos <>
            // Esto arregla el error "Cannot infer type parameter T"
            val request = call.receive<CreateUserRequest>()

            val resultado = transaction {
                // 1. Buscamos si el usuario ya existe
                val userExiste = Users.select {
                    (Users.firebaseUid eq request.firebaseUid) or (Users.email eq request.email)
                }.singleOrNull()

                if (userExiste == null) {
                    // 2. Si NO existe, lo creamos
                    val idNuevo = Users.insert {
                        it[firebaseUid] = request.firebaseUid
                        it[email] = request.email
                        it[fullName] = request.fullName
                        it[role] = "student"
                    }.get(Users.id)

                    HttpStatusCode.Created to CreateUserResponse(idNuevo, "Usuario registrado exitosamente")
                } else {
                    // 3. Si YA existe, devolvemos info existente
                    val idExistente = userExiste[Users.id]
                    HttpStatusCode.OK to CreateUserResponse(idExistente, "El usuario ya se encuentra registrado")
                }
            }

            call.respond(resultado.first, resultado.second)

        } catch (e: Exception) {
            application.log.error("Error en registro de usuario", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Error al procesar el registro: ${e.message}"))
        }
    }
}