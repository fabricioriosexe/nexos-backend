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

/**
 * Módulo de rutas para la gestión del Ciclo de Vida de la Entrevista.
 * ACTUALIZADO: Soporte para Multi-idioma y Target Focus.
 */
fun Route.interviewRoutes() {

    // Inicialización del cliente de IA
    val apiKey = application.environment.config.propertyOrNull("vapi.apiKey")?.getString() ?: ""
    val baseUrl = application.environment.config.propertyOrNull("vapi.baseUrl")?.getString() ?: "https://api.vapi.ai"
    val vapiClient = VapiClient(apiKey, baseUrl)

    // --- RUTA 1: CREAR ENTREVISTA (POST) ---
    post("/interviews") {
        try {
            val request = call.receive<CreateInterviewRequest>()

            // 1. Lógica de Nivel (Simplificamos aquí porque el VapiClient se encarga del resto)
            val instruction = when (request.level.lowercase()) {
                "trainee" -> "Nivel: TRAINEE. Preguntas teóricas y básicas."
                "junior" -> "Nivel: JUNIOR. Preguntas estándar."
                "senior" -> "Nivel: SENIOR. Preguntas de arquitectura y casos bordes."
                else -> "Nivel: ESTÁNDAR."
            }

            // 2. Orquestación con Vapi (AHORA CON 4 PARÁMETROS)
            // Le pasamos el idioma y la empresa para que VapiClient configure la voz y el prompt
            val assistantIdGenerado = vapiClient.createEphemeralAssistant(
                promptBase = instruction,
                topic = request.topic,
                language = request.language,    // 👈 NUEVO: Viene del Frontend ("es" o "en")
                targetFocus = request.targetFocus // 👈 NUEVO: Empresa (ej. "Mercado Libre")
            )

            // 3. Persistencia Transaccional (Exposed)
            val responseObj = transaction {
                val userRow = Users.select { Users.firebaseUid eq request.firebaseUid }.singleOrNull()

                if (userRow != null) {
                    val userIdReal = userRow[Users.id]

                    // Insertamos la nueva entrevista con estado "IN_PROGRESS"
                    val newId = Interviews.insert {
                        it[userId] = userIdReal
                        it[topic] = request.topic
                        it[level] = request.level
                        it[assistantId] = assistantIdGenerado
                        it[status] = "IN_PROGRESS"
                    }.get(Interviews.id)

                    // Retornamos el DTO listo
                    InterviewResponse(
                        newId,
                        request.topic,
                        "IN_PROGRESS",
                        java.time.LocalDateTime.now().toString(),
                        assistantIdGenerado,
                        "assets/icons/default.png"
                    )
                } else null
            }

            if (responseObj == null) call.respond(HttpStatusCode.NotFound, "Usuario no encontrado. Verifique el UID.")
            else call.respond(HttpStatusCode.Created, responseObj)

        } catch (e: Exception) {
            application.log.error("Error creando entrevista", e)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // --- RUTA 2: ÚLTIMO RESULTADO (GET) ---
    // (Esta parte queda igual, usa JDBC directo para lectura rápida)
    get("/results/latest") {
        val firebaseUid = call.request.queryParameters["uid"]

        if (firebaseUid == null) {
            call.respond(HttpStatusCode.BadRequest, "Falta el parámetro 'uid'")
            return@get
        }

        try {
            val dbUrl = "jdbc:mysql://localhost:3306/nexos?allowPublicKeyRetrieval=true&useSSL=false"
            val conn = DriverManager.getConnection(dbUrl, "root", "admin")

            var result: InterviewResultResponse? = null

            val userQuery = "SELECT id FROM users WHERE firebase_uid = ?"
            val stmtUser = conn.prepareStatement(userQuery)
            stmtUser.setString(1, firebaseUid)
            val rsUser = stmtUser.executeQuery()

            if (rsUser.next()) {
                val internalUserId = rsUser.getLong("id")

                val query = """
                    SELECT id, topic, level, score, feedback, created_at 
                    FROM interview_results 
                    WHERE user_id = ? 
                    ORDER BY id DESC LIMIT 1
                """.trimIndent()

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
            else call.respond(HttpStatusCode.NotFound, "No hay entrevistas realizadas aún.")

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, "Error de base de datos: ${e.message}")
        }
    }

    // --- RUTA 3: HISTORIAL COMPLETO (GET) ---
    get("/results/history") {
        val firebaseUid = call.request.queryParameters["uid"]

        if (firebaseUid == null) {
            call.respond(HttpStatusCode.BadRequest, "Falta el uid")
            return@get
        }

        try {
            val dbUrl = "jdbc:mysql://localhost:3306/nexos?allowPublicKeyRetrieval=true&useSSL=false"
            val conn = DriverManager.getConnection(dbUrl, "root", "admin")
            val list = mutableListOf<InterviewResultResponse>()

            val userQuery = "SELECT id FROM users WHERE firebase_uid = ?"
            val stmtUser = conn.prepareStatement(userQuery)
            stmtUser.setString(1, firebaseUid)
            val rsUser = stmtUser.executeQuery()

            if (rsUser.next()) {
                val internalUserId = rsUser.getLong("id")

                val query = """
                    SELECT * FROM (
                        SELECT id, topic, level, score, feedback, created_at 
                        FROM interview_results 
                        WHERE user_id = ? 
                        ORDER BY created_at DESC LIMIT 10
                    ) sub ORDER BY created_at ASC
                """.trimIndent()

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
            application.log.error("Error obteniendo historial", e)
            call.respond(HttpStatusCode.InternalServerError, emptyList<String>())
        }
    }
}