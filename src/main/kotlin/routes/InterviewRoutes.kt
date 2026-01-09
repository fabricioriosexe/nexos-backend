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
 * Maneja: Creación de la sala (Vapi), Asignación de asistentes y Consulta de resultados.
 */
fun Route.interviewRoutes() {

    // Inicialización del cliente de IA
    // Se recuperan las credenciales del application.yaml para no hardcodear claves.
    val apiKey = application.environment.config.propertyOrNull("vapi.apiKey")?.getString() ?: ""
    val baseUrl = application.environment.config.propertyOrNull("vapi.baseUrl")?.getString() ?: "https://api.vapi.ai"
    val vapiClient = VapiClient(apiKey, baseUrl)

    // --- RUTA 1: CREAR ENTREVISTA (POST) ---
    /**
     * Inicia una nueva simulación.
     * 1. Define la personalidad de la IA según el nivel (Trainee/Junior/Senior).
     * 2. Contacta a Vapi para crear un asistente efímero.
     * 3. Registra la entrevista en la Base de Datos vinculada al usuario.
     */
    post("/interviews") {
        try {
            val request = call.receive<CreateInterviewRequest>()

            // 1. Lógica de Personalización (Dynamic Prompting)
            // Ajustamos la agresividad y complejidad del entrevistador según el nivel seleccionado.
            val instruction = when (request.level.lowercase()) {
                "trainee" -> "Nivel: TRAINEE/MENTOR. Haz preguntas muy básicas y teóricas. Sé paciente y ayuda si se traba."
                "junior" -> "Nivel: JUNIOR. Haz preguntas estándar de mercado. Sé profesional pero accesible."
                "senior" -> "Nivel: SENIOR/PRINCIPAL. Haz preguntas complejas de arquitectura y casos de borde. Sé ESTRICTO y busca fallos."
                else -> "Nivel: ESTÁNDAR."
            }

            // Inyectamos el contexto específico (Topic + Nivel) en el prompt del sistema
            val systemPrompt = """
                Contexto: Entrevistando candidato nivel ${request.level} en ${request.topic}.
                Instrucción: $instruction
            """.trimIndent()

            // 2. Orquestación con Vapi (Llamada a API Externa)
            val assistantIdGenerado = vapiClient.createEphemeralAssistant(systemPrompt, request.topic)

            // 3. Persistencia Transaccional
            // Usamos Exposed para garantizar que la entrevista se guarde solo si el usuario existe.
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

                    // Retornamos el DTO listo para que el Frontend inicie la llamada
                    InterviewResponse(
                        newId,
                        request.topic,
                        "IN_PROGRESS",
                        java.time.LocalDateTime.now().toString(),
                        assistantIdGenerado,
                        "assets/icons/default.png" // Icono por defecto (placeholder)
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
    /**
     * Obtiene el feedback más reciente para mostrar en el Dashboard ("Tu último rendimiento").
     * Implementa seguridad por UID para que un usuario no vea datos de otro.
     */
    get("/results/latest") {
        val firebaseUid = call.request.queryParameters["uid"]

        if (firebaseUid == null) {
            call.respond(HttpStatusCode.BadRequest, "Falta el parámetro 'uid'")
            return@get
        }

        try {
            // Usamos JDBC directo para consultas de lectura optimizadas
            val dbUrl = "jdbc:mysql://localhost:3306/nexos?allowPublicKeyRetrieval=true&useSSL=false"
            val conn = DriverManager.getConnection(dbUrl, "root", "admin")

            var result: InterviewResultResponse? = null

            // Paso A: Resolver identidad (Firebase UID -> Internal ID)
            val userQuery = "SELECT id FROM users WHERE firebase_uid = ?"
            val stmtUser = conn.prepareStatement(userQuery)
            stmtUser.setString(1, firebaseUid)
            val rsUser = stmtUser.executeQuery()

            if (rsUser.next()) {
                val internalUserId = rsUser.getLong("id")

                // Paso B: Obtener último resultado validado por user_id
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
    /**
     * Obtiene el historial para los gráficos de progreso.
     * Estrategia SQL: "Last 10 ordered chronologically".
     * Obtenemos los últimos 10 registros, pero los reordenamos por fecha ascendente
     * para que el gráfico de línea se dibuje de izquierda a derecha correctamente.
     */
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

            // Paso A: Resolver identidad
            val userQuery = "SELECT id FROM users WHERE firebase_uid = ?"
            val stmtUser = conn.prepareStatement(userQuery)
            stmtUser.setString(1, firebaseUid)
            val rsUser = stmtUser.executeQuery()

            if (rsUser.next()) {
                val internalUserId = rsUser.getLong("id")

                // Paso B: Query Anidada para Gráficos
                // 1. Subquery: Obtiene los últimos 10 (ORDER BY created_at DESC)
                // 2. Query Principal: Los reordena cronológicamente (ORDER BY created_at ASC)
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