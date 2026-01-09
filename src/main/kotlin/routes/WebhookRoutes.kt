package com.ff.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.DriverManager

// --- DTOs (Data Transfer Objects) para el parsing del JSON de Vapi ---
@Serializable data class VapiWebhookEvent(val message: VapiMessagePayload)
@Serializable data class VapiMessagePayload(val type: String, val artifact: VapiArtifact? = null, val call: VapiCall? = null)
@Serializable data class VapiCall(val assistantId: String? = null)
@Serializable data class VapiArtifact(val transcript: String? = null)

/**
 * Módulo de rutas para Webhooks.
 * Funciona como el "oído" del sistema, recibiendo eventos asíncronos desde Vapi.ai.
 */
fun Route.webhookRoutes() {

    post("/vapi/webhook") {
        try {
            // 1. RECEPCIÓN: Leemos el payload crudo para evitar errores de serialización prematuros
            val bodyText = call.receiveText()

            // 2. FILTRADO DE EVENTOS:
            // Vapi envía muchos eventos (speech-start, transcript, etc.).
            // Solo nos interesa el "end-of-call-report" que contiene el resumen final.
            if (bodyText.contains("end-of-call-report")) {

                val jsonParser = Json { ignoreUnknownKeys = true }
                val event = jsonParser.decodeFromString<VapiWebhookEvent>(bodyText)

                val transcript = event.message.artifact?.transcript ?: ""
                val assistantId = event.message.call?.assistantId

                println("\n🕵️ --- REPORTE RECIBIDO: Iniciando Procesamiento ---")

                // --- 3. EXTRACCIÓN DE METADATA (REGEX ENGINE) ---
                // El desafío es extraer datos estructurados (puntaje) de texto no estructurado (voz).

                // Estrategia A: Buscar patrón explícito "Puntaje: XX"
                var scoreRegex = Regex("""(?:PUNTAJE|NOTA|SCORE)[^\d]{0,10}(\d{1,3})""", RegexOption.IGNORE_CASE)
                var match = scoreRegex.find(transcript)

                // Estrategia B (Fallback): Si falla A, buscar un número aislado cercano a la palabra "Feedback"
                if (match == null) {
                    scoreRegex = Regex("""\b(\d{1,3})\b[\s\S]{0,20}(?:Feedback|Comentario)""", RegexOption.IGNORE_CASE)
                    match = scoreRegex.find(transcript)
                }

                if (match != null) {
                    val score = match.groupValues[1].toInt()

                    // 4. LIMPIEZA QUIRÚRGICA DEL FEEDBACK (SANITIZATION)
                    // Eliminamos el puntaje del texto para no repetir información y limpiamos etiquetas de la IA.
                    val endOfScoreIndex = match.range.last + 1
                    var rawFeedback = transcript.substring(endOfScoreIndex).trim()

                    // Regex para eliminar encabezados redundantes como "Feedback: ..."
                    val cleanupRegex = Regex("""(?s)^[\.,\-\s\n\r]*(?:Feedback|Comentario|FEEDBACK|Analysis)[:\.\s,\n\r]*""")
                    var cleanFeedback = rawFeedback.replaceFirst(cleanupRegex, "").trim()

                    // Eliminación de ruido de la transcripción (etiquetas de roles)
                    cleanFeedback = cleanFeedback
                        .trimStart(',', '.', '-', ':', ' ', '\n', '\r')

                    if (cleanFeedback.contains("User:", ignoreCase = true)) {
                        cleanFeedback = cleanFeedback.substringBefore("User:")
                    }
                    cleanFeedback = cleanFeedback.replace("AI:", "", ignoreCase = true).trim()

                    // Formato final: Capitalización
                    if (cleanFeedback.isNotEmpty()) {
                        cleanFeedback = cleanFeedback.replaceFirstChar { it.uppercase() }
                    } else {
                        cleanFeedback = "Sin feedback detallado."
                    }

                    // 5. PERSISTENCIA Y RELACIÓN DE DATOS
                    // Usamos JDBC directo para esta operación crítica.
                    // TODO: A futuro, mover esto a un Service con Connection Pool para mayor eficiencia.
                    val dbUrl = "jdbc:mysql://localhost:3306/nexos?allowPublicKeyRetrieval=true&useSSL=false"
                    val conn = DriverManager.getConnection(dbUrl, "root", "admin")

                    var realTopic = "Entrevista Técnica"
                    var realLevel = "Junior"
                    var userId: Long? = null // Fundamental para mostrar el historial al alumno correcto

                    if (assistantId != null) {
                        try {
                            // JOINT LÓGICO: Recuperamos quién es el dueño de esta entrevista usando el assistant_id
                            val query = "SELECT user_id, topic, level FROM interviews WHERE assistant_id = ? LIMIT 1"
                            val stmt = conn.prepareStatement(query)
                            stmt.setString(1, assistantId)
                            val rs = stmt.executeQuery()

                            if (rs.next()) {
                                userId = rs.getLong("user_id")
                                realTopic = rs.getString("topic")
                                realLevel = rs.getString("level") ?: "Junior"
                            }
                        } catch (e: Exception) { println("Error buscando metadata: ${e.message}") }
                    }

                    // Insertamos el resultado final vinculado al usuario
                    val insertSQL = "INSERT INTO interview_results (topic, level, score, feedback, transcript, user_id) VALUES (?, ?, ?, ?, ?, ?)"
                    conn.prepareStatement(insertSQL).apply {
                        setString(1, realTopic)
                        setString(2, realLevel)
                        setInt(3, score)
                        setString(4, cleanFeedback)
                        setString(5, transcript)
                        // Manejo de nulos para JDBC
                        if (userId != null) setLong(6, userId) else setNull(6, java.sql.Types.BIGINT)
                        executeUpdate()
                    }
                    conn.close()
                    println("✅ Resultado guardado exitosamente. Usuario: $userId | Score: $score | Tópico: $realTopic")
                } else {
                    println("⚠️ Advertencia: No se detectó patrón de puntaje en la transcripción: '$transcript'")
                }
            }
            // Siempre responder 200 OK a Vapi para confirmar recepción
            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            println("❌ Error crítico en Webhook: ${e.message}")
            // Respondemos OK para evitar que Vapi reintente infinitamente en caso de error lógico nuestro
            call.respond(HttpStatusCode.OK)
        }
    }
}