package com.ff.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import com.ff.models.*
import com.ff.dtos.*
import com.ff.vapi.VapiClient
import java.time.LocalDateTime

/**
 * Módulo de rutas para Nexos AI.
 * Sincronizado para mostrar el feedback desde la tabla interview_results.
 */
fun Route.interviewRoutes() {

    val apiKey = application.environment.config.propertyOrNull("vapi.apiKey")?.getString() ?: ""
    val baseUrl = application.environment.config.propertyOrNull("vapi.baseUrl")?.getString() ?: "https://api.vapi.ai"
    val vapiClient = VapiClient(apiKey, baseUrl)

    // --- RUTA 1: CREAR ENTREVISTA (POST) ---
    post("/interviews") {
        try {
            val request = call.receive<CreateInterviewRequest>()
            val instruction = when (request.level.lowercase()) {
                "trainee" -> "Nivel: TRAINEE. Preguntas teóricas y básicas."
                "junior" -> "Nivel: JUNIOR. Preguntas estándar."
                "senior" -> "Nivel: SENIOR. Preguntas de arquitectura y casos bordes."
                else -> "Nivel: ESTÁNDAR."
            }

            val assistantIdGenerado = vapiClient.createEphemeralAssistant(
                promptBase = instruction,
                topic = request.topic,
                language = request.language,
                targetFocus = request.targetFocus
            )

            val responseObj = transaction {
                val userRow = Users.select { Users.firebaseUid eq request.firebaseUid }.singleOrNull()
                userRow?.let {
                    val userIdReal = it[Users.id]
                    val newId = Interviews.insert {
                        it[userId] = userIdReal
                        it[topic] = request.topic
                        it[level] = request.level
                        it[assistantId] = assistantIdGenerado
                        it[status] = "IN_PROGRESS"
                    }.get(Interviews.id)

                    InterviewResponse(
                        id = newId,
                        topic = request.topic,
                        status = "IN_PROGRESS",
                        date = LocalDateTime.now().toString(),
                        assistantId = assistantIdGenerado,
                        imageUrl = "assets/icons/default.png"
                    )
                }
            }

            if (responseObj == null) call.respond(HttpStatusCode.NotFound, "Usuario no encontrado.")
            else call.respond(HttpStatusCode.Created, responseObj)
        } catch (e: Exception) {
            application.log.error("Error creando entrevista", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // --- RUTA: COPILOTO AI (POST) ---
    post("/copilot") {
        try {
            val request = call.receive<CopilotRequest>()

            val sugerenciaDinamica = when {
                request.topic.contains("Java", ignoreCase = true) ->
                    "Para Java, enfócate en conceptos de POO, Colecciones o Streams. Si la pregunta es sobre Spring, menciona Inyección de Dependencias."
                request.topic.contains("React", ignoreCase = true) ->
                    "En React, destaca el uso de Hooks (useEffect, useState) y cómo manejas el ciclo de vida o el estado global."
                else -> "Analiza la pregunta técnica y trata de explicar tu razonamiento lógico paso a paso si no conoces la sintaxis exacta."
            }

            val promptAyuda = """
            💡 ESTRATEGIA PARA: ${request.topic}
            
            SOBRE LA PREGUNTA: "${request.question}"
            
            CONSEJO TÉCNICO:
            $sugerenciaDinamica
            
            RECORDATORIO:
            1. Usá el método STAR (Situación, Tarea, Acción, Resultado).
            2. Menciona palabras clave como 'Escalabilidad' o 'Buenas Prácticas'.
        """.trimIndent()

            call.respond(HttpStatusCode.OK, CopilotResponse(hint = promptAyuda))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, CopilotResponse(hint = "Mantené la calma y estructurá tu respuesta."))
        }
    }

    // --- RUTA 2: ÚLTIMO RESULTADO (GET) ---
    // Optimizado para traer el feedback más reciente de la tabla correcta
    get("/results/latest") {
        val firebaseUid = call.request.queryParameters["uid"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Falta UID")
        try {
            val result = transaction {
                val internalUserId = Users.select { Users.firebaseUid eq firebaseUid }
                    .map { it[Users.id] }.singleOrNull() ?: return@transaction null

                var latestResult: InterviewResultResponse? = null

                // Consultamos explícitamente la tabla donde el Webhook guarda el reporte final
                exec("""
                    SELECT id, topic, level, score, feedback, created_at 
                    FROM interview_results 
                    WHERE user_id = $internalUserId 
                    ORDER BY created_at DESC LIMIT 1
                """) { rs ->
                    if (rs.next()) {
                        latestResult = InterviewResultResponse(
                            id = rs.getInt("id"),
                            score = rs.getInt("score"),
                            feedback = rs.getString("feedback") ?: "Feedback procesando...",
                            topic = rs.getString("topic"),
                            level = rs.getString("level") ?: "Junior",
                            date = rs.getTimestamp("created_at").toString()
                        )
                    }
                }
                latestResult
            }
            if (result != null) call.respond(HttpStatusCode.OK, result)
            else call.respond(HttpStatusCode.NotFound, "No se encontraron resultados recientes.")
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, "Error al recuperar último resultado: ${e.message}")
        }
    }

    // --- RUTA 3: HISTORIAL COMPLETO (GET) ---
    get("/results/history") {
        val firebaseUid = call.request.queryParameters["uid"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        try {
            val history = transaction {
                val internalUserId = Users.select { Users.firebaseUid eq firebaseUid }
                    .map { it[Users.id] }.singleOrNull() ?: return@transaction emptyList()

                val list = mutableListOf<InterviewResultResponse>()

                // Orden descendente por fecha para que el dashboard vea lo más nuevo primero
                exec("""
                    SELECT id, topic, level, score, feedback, created_at 
                    FROM interview_results 
                    WHERE user_id = $internalUserId 
                    ORDER BY created_at DESC LIMIT 10
                """) { rs ->
                    while (rs.next()) {
                        list.add(InterviewResultResponse(
                            id = rs.getInt("id"),
                            score = rs.getInt("score"),
                            feedback = rs.getString("feedback") ?: "Sin feedback detallado",
                            topic = rs.getString("topic"),
                            level = rs.getString("level") ?: "Junior",
                            date = rs.getTimestamp("created_at").toString()
                        ))
                    }
                }
                list
            }
            call.respond(HttpStatusCode.OK, history)
        } catch (e: Exception) {
            application.log.error("Error obteniendo historial", e)
            call.respond(HttpStatusCode.InternalServerError, emptyList<String>())
        }
    }
}