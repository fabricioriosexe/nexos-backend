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
import java.sql.DriverManager

fun Route.interviewRoutes() {

    val apiKey = application.environment.config.propertyOrNull("vapi.apiKey")?.getString() ?: ""
    val baseUrl = application.environment.config.propertyOrNull("vapi.baseUrl")?.getString() ?: "https://api.vapi.ai"
    val vapiClient = VapiClient(apiKey, baseUrl)

    // --- RUTA 1: CREAR ENTREVISTA (POST) ---
    post("/interviews") {
        try {
            val request = call.receive<CreateInterviewRequest>()

            // 1. Personalidad según nivel
            val instruction = when (request.level.lowercase()) {
                "trainee" -> "Nivel: TRAINEE/MENTOR. Haz preguntas muy básicas y teóricas. Sé paciente."
                "junior" -> "Nivel: JUNIOR. Haz preguntas estándar. Sé profesional."
                "senior" -> "Nivel: SENIOR/PRINCIPAL. Haz preguntas complejas de arquitectura. Sé ESTRICTO."
                else -> "Nivel: ESTÁNDAR."
            }

            val systemPrompt = """
                Contexto: Entrevistando candidato nivel ${request.level} en ${request.topic}.
                Instrucción: $instruction
            """.trimIndent()

            // 2. Crear en Vapi
            val assistantIdGenerado = vapiClient.createEphemeralAssistant(systemPrompt, request.topic)

            // 3. Guardar en DB con LEVEL
            val responseObj = transaction {
                val userRow = Users.select { Users.firebaseUid eq request.firebaseUid }.singleOrNull()
                if (userRow != null) {
                    val userIdReal = userRow[Users.id]
                    val newId = Interviews.insert {
                        it[userId] = userIdReal
                        it[topic] = request.topic
                        it[level] = request.level
                        it[assistantId] = assistantIdGenerado
                        it[status] = "IN_PROGRESS"
                    }.get(Interviews.id)

                    InterviewResponse(
                        newId, request.topic, "IN_PROGRESS",
                        java.time.LocalDateTime.now().toString(), assistantIdGenerado, "assets/icons/default.png"
                    )
                } else null
            }

            if (responseObj == null) call.respond(HttpStatusCode.NotFound, "Usuario no encontrado")
            else call.respond(HttpStatusCode.Created, responseObj)

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // --- RUTA 2: ÚLTIMO RESULTADO FILTRADO POR USUARIO (GET) ---
    get("/results/latest") {
        val firebaseUid = call.request.queryParameters["uid"] // <--- PEDIMOS EL UID

        if (firebaseUid == null) {
            call.respond(HttpStatusCode.BadRequest, "Falta el uid")
            return@get
        }

        try {
            val dbUrl = "jdbc:mysql://localhost:3306/nexos?allowPublicKeyRetrieval=true&useSSL=false"
            val conn = DriverManager.getConnection(dbUrl, "root", "admin")

            var result: InterviewResultResponse? = null

            // 1. Buscamos el ID interno del usuario
            val userQuery = "SELECT id FROM users WHERE firebase_uid = ?"
            val stmtUser = conn.prepareStatement(userQuery)
            stmtUser.setString(1, firebaseUid)
            val rsUser = stmtUser.executeQuery()

            if (rsUser.next()) {
                val internalUserId = rsUser.getLong("id")

                // 2. Buscamos el último resultado DE ESE USUARIO (usando user_id)
                val query = "SELECT id, topic, level, score, feedback, created_at FROM interview_results WHERE user_id = ? ORDER BY id DESC LIMIT 1"
                val stmt = conn.prepareStatement(query)
                stmt.setLong(1, internalUserId)
                val rs = stmt.executeQuery()

                if (rs.next()) {
                    result = InterviewResultResponse(
                        rs.getInt("id"), rs.getInt("score"),
                        rs.getString("feedback") ?: "", rs.getString("topic"),
                        rs.getString("level") ?: "Junior",
                        rs.getTimestamp("created_at").toString()
                    )
                }
            }
            conn.close()
            if (result != null) call.respond(HttpStatusCode.OK, result)
            else call.respond(HttpStatusCode.NotFound, "Vacío")
        } catch (e: Exception) { call.respond(HttpStatusCode.InternalServerError, e.message.toString()) }
    }

    // --- RUTA 3: HISTORIAL FILTRADO POR USUARIO (GET) ---
    get("/results/history") {
        val firebaseUid = call.request.queryParameters["uid"] // <--- PEDIMOS UID

        if (firebaseUid == null) {
            call.respond(HttpStatusCode.BadRequest, "Falta el uid")
            return@get
        }

        try {
            val dbUrl = "jdbc:mysql://localhost:3306/nexos?allowPublicKeyRetrieval=true&useSSL=false"
            val conn = DriverManager.getConnection(dbUrl, "root", "admin")
            val list = mutableListOf<InterviewResultResponse>()

            // 1. Buscamos ID interno
            val userQuery = "SELECT id FROM users WHERE firebase_uid = ?"
            val stmtUser = conn.prepareStatement(userQuery)
            stmtUser.setString(1, firebaseUid)
            val rsUser = stmtUser.executeQuery()

            if (rsUser.next()) {
                val internalUserId = rsUser.getLong("id")

                // 2. Filtramos por ese ID (user_id)
                val query = "SELECT * FROM (SELECT id, topic, level, score, feedback, created_at FROM interview_results WHERE user_id = ? ORDER BY created_at DESC LIMIT 10) sub ORDER BY created_at ASC"
                val stmt = conn.prepareStatement(query)
                stmt.setLong(1, internalUserId)
                val rs = stmt.executeQuery()

                while (rs.next()) {
                    list.add(
                        InterviewResultResponse(
                            rs.getInt("id"), rs.getInt("score"),
                            rs.getString("feedback") ?: "", rs.getString("topic"),
                            rs.getString("level") ?: "Junior",
                            rs.getTimestamp("created_at").toString()
                        )
                    )
                }
            }
            conn.close()
            call.respond(HttpStatusCode.OK, list)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, emptyList<String>())
        }
    }
}