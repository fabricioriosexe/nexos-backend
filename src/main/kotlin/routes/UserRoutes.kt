package com.ff.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import com.ff.models.Users
import com.ff.dtos.* // 👈 IMPORTANTE: Esto trae CreateUserRequest, UpdateUserRequest, etc.

/**
 * Módulo de rutas para la Gestión de Usuarios.
 * Maneja el ciclo de vida: Registro inicial (Onboarding) y actualización de perfil.
 */
fun Route.userRoutes() {

    /**
     * POST /users
     * Endpoint idempotente para registro o login.
     * 1. Recibe el UID de Firebase.
     * 2. Si NO existe -> Lo crea (Registro).
     * 3. Si YA existe -> Devuelve el ID existente (Login silencioso).
     */
    post("/users") {
        try {
            val request = call.receive<CreateUserRequest>()

            val resultado = transaction {
                // Búsqueda defensiva: Verificamos por UID o Email
                val userExiste = Users.select {
                    (Users.firebaseUid eq request.firebaseUid) or (Users.email eq request.email)
                }.singleOrNull()

                if (userExiste == null) {
                    // Caso: Usuario Nuevo
                    val idNuevo = Users.insert {
                        it[firebaseUid] = request.firebaseUid
                        it[email] = request.email
                        it[fullName] = request.fullName
                        it[role] = "student"
                        // photoUrl queda null por defecto al registrarse, o puedes poner uno default
                    }.get(Users.id)

                    HttpStatusCode.Created to CreateUserResponse(idNuevo, "Usuario registrado exitosamente")
                } else {
                    // Caso: Usuario Recurrente
                    val idExistente = userExiste[Users.id]
                    HttpStatusCode.OK to CreateUserResponse(idExistente, "El usuario ya se encuentra registrado")
                }
            }
            call.respond(resultado.first, resultado.second)

        } catch (e: Exception) {
            application.log.error("Error crítico en registro de usuario", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Error interno: ${e.message}"))
        }
    }

    /**
     * PUT /users/{firebaseUid}
     * Actualización de perfil.
     * Recibe nombre y/o foto nueva desde el Frontend (Cloudinary).
     */
    put("/users/{firebaseUid}") {
        val firebaseUid = call.parameters["firebaseUid"]

        if (firebaseUid == null) {
            call.respond(HttpStatusCode.BadRequest, "Falta el UID en la URL")
            return@put
        }

        try {
            // Usamos el DTO que definiste en UserDtos.kt
            val request = call.receive<UpdateUserRequest>()

            transaction {
                Users.update({ Users.firebaseUid eq firebaseUid }) {
                    // Siempre actualizamos el nombre
                    it[fullName] = request.fullName

                    // Lógica Condicional: Solo tocamos la foto si viene un valor real (no null)
                    if (request.photoUrl != null) {
                        it[photoUrl] = request.photoUrl
                    }
                }
            }

            call.respond(HttpStatusCode.OK, mapOf("message" to "Perfil actualizado correctamente"))

        } catch (e: Exception) {
            application.log.error("Error actualizando perfil", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Error interno: ${e.message}"))
        }
    }
}