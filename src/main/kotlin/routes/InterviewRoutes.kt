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

fun Route.interviewRoutes() {

    val apiKey = application.environment.config.propertyOrNull("vapi.apiKey")?.getString() ?: ""
    val baseUrl = application.environment.config.propertyOrNull("vapi.baseUrl")?.getString() ?: "https://api.vapi.ai"
    val vapiClient = VapiClient(apiKey, baseUrl)

    post("/interviews") {
        try {
            val request = call.receive<CreateInterviewRequest>()

            // 1. VALIDACIÓN
            if (request.topic.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "El tema es obligatorio"))
                return@post
            }
            if (request.topic.length > 50) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "El tema es muy largo (máx 50 caracteres)"))
                return@post
            }

            // 2. INTELIGENCIA DE CONTEXTO
            val isTech = request.topic.lowercase().any {
                it.toString() in listOf("java", "kotlin", "python", "dev", "programador", "sql", "react", "datos", "node")
            }

            val roleName = if (isTech) "Senior Technical Recruiter" else "Expert Interviewer Coach"
            val objective = if (isTech) "Assess technical depth and coding skills." else "Assess communication skills and practical knowledge."

            val systemPrompt = """
                Role: You are an $roleName specialized in ${request.topic}.
                Objective: $objective
                
                Instructions:
                1. Start by introducing yourself briefly.
                2. Ask ONE question at a time.
                3. If the answer is vague, ask a follow-up question.
                4. Keep responses concise (max 3 sentences).
                5. Be professional and encouraging.
            """.trimIndent()

            // 3. LLAMADA A VAPI
            val assistantIdGenerado = vapiClient.createEphemeralAssistant(systemPrompt, request.topic)

            // 4. LÓGICA DE IMAGEN (Para tu diseño)
            val iconUrl = when {
                request.topic.lowercase().contains("java") -> "assets/icons/java.png"
                request.topic.lowercase().contains("python") -> "assets/icons/python.png"
                request.topic.lowercase().contains("english") -> "assets/icons/english.png"
                else -> "assets/icons/default.png"
            }

            // 5. GUARDADO EN DB
            val responseObj = transaction {
                val userRow = Users.select { Users.firebaseUid eq request.firebaseUid }.singleOrNull()

                if (userRow == null) {
                    null
                } else {
                    val userIdReal = userRow[Users.id]
                    val newId = Interviews.insert {
                        it[userId] = userIdReal
                        it[topic] = request.topic
                        it[assistantId] = assistantIdGenerado
                        it[status] = "IN_PROGRESS"
                    }.get(Interviews.id)

                    InterviewResponse(
                        id = newId,
                        topic = request.topic,
                        status = "IN_PROGRESS",
                        date = java.time.LocalDateTime.now().toString(),
                        assistantId = assistantIdGenerado,
                        imageUrl = iconUrl
                    )
                }
            }

            // 6. RESPUESTA FINAL
            if (responseObj == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Usuario no encontrado"))
            } else {
                call.respond(HttpStatusCode.Created, responseObj)
            }

        } catch (e: Exception) {
            application.log.error("CRITICAL ERROR: ${e.message}", e)
            val msg = if (e.message?.contains("Vapi") == true) "Error de IA, intenta luego" else "Error interno"
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to msg, "details" to (e.message ?: "")))
        }
    }
}