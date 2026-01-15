package com.ff.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import com.ff.models.*
import java.sql.Types

@Serializable data class VapiWebhookEvent(val message: VapiMessagePayload)
@Serializable data class VapiMessagePayload(val type: String, val artifact: VapiArtifact? = null, val call: VapiCall? = null)
@Serializable data class VapiCall(val assistantId: String? = null)
@Serializable data class VapiArtifact(val transcript: String? = null)

fun Route.webhookRoutes() {

    post("/vapi/webhook") {
        try {
            val bodyText = call.receiveText()

            // Solo procesamos cuando Vapi envía el reporte final
            if (bodyText.contains("end-of-call-report")) {
                val jsonParser = Json { ignoreUnknownKeys = true }
                val event = jsonParser.decodeFromString<VapiWebhookEvent>(bodyText)
                val transcript = event.message.artifact?.transcript ?: ""
                val assistantId = event.message.call?.assistantId

                application.log.info("🕵️ --- REPORTE RECIBIDO: Procesando asistente $assistantId ---")

                // --- 1. EXTRACCIÓN DE PUNTAJE (Lógica Anti-Alucinación) ---
                // Buscamos patrones específicos para evitar que un "100" en el saludo cuente como nota.
                var scoreRegex = Regex("""(?:PUNTAJE|NOTA|SCORE|CALIFICACIÓN)[^\d]{0,10}(\d{1,3})""", RegexOption.IGNORE_CASE)
                var match = scoreRegex.find(transcript)

                // Fallback: Buscar número antes de la palabra Feedback si el anterior falla
                if (match == null) {
                    scoreRegex = Regex("""\b(\d{1,3})\b[\s\S]{0,30}(?:Feedback|Comentario|Análisis)""", RegexOption.IGNORE_CASE)
                    match = scoreRegex.find(transcript)
                }

                if (match != null) {
                    val score = match.groupValues[1].toInt().coerceIn(0, 100) // Aseguramos rango 0-100

                    // --- 2. LIMPIEZA DE FEEDBACK ---
                    val endOfScoreIndex = match.range.last + 1
                    val rawFeedback = transcript.substring(endOfScoreIndex).trim()

                    val cleanupRegex = Regex("""(?s)^[.,\-\s\n\r]*(?:Feedback|Comentario|FEEDBACK|Analysis|Resumen)[:.\s,\n\r]*""")
                    var cleanFeedback = rawFeedback.replaceFirst(cleanupRegex, "").trim()
                        .substringBefore("User:")
                        .replace("AI:", "", ignoreCase = true)
                        .trimStart(',', '.', '-', ':', ' ', '\n', '\r')
                        .replaceFirstChar { it.uppercase() }

                    if (cleanFeedback.isEmpty() || cleanFeedback.length < 5) {
                        cleanFeedback = "Entrevista finalizada. No se proporcionó feedback detallado en la transcripción."
                    }

                    // --- 3. PERSISTENCIA EN BASE DE DATOS (Solución a Errores de Tipos) ---
                    transaction {
                        var realTopic = "Entrevista Técnica"
                        var realLevel = "Junior"
                        var userId: Long? = null

                        // Recuperamos metadata de la entrevista original
                        if (assistantId != null) {
                            val interviewRow = Interviews.select { Interviews.assistantId eq assistantId }.singleOrNull()
                            if (interviewRow != null) {
                                userId = interviewRow[Interviews.userId]
                                realTopic = interviewRow[Interviews.topic]
                                realLevel = interviewRow[Interviews.level]

                                // Actualizamos estado de la entrevista
                                Interviews.update({ Interviews.assistantId eq assistantId }) {
                                    it[status] = "COMPLETED"
                                    it[Interviews.score] = score
                                    it[feedbackSummary] = cleanFeedback
                                }
                            }
                        }

                        // Inserción en historial usando JDBC nativo para evitar conflictos con Exposed
                        val sql = "INSERT INTO interview_results (topic, level, score, feedback, transcript, user_id, created_at) VALUES (?, ?, ?, ?, ?, ?, NOW())"
                        val jdbcConn = this.connection.connection as java.sql.Connection

                        jdbcConn.prepareStatement(sql).use { stmt ->
                            stmt.setString(1, realTopic)
                            stmt.setString(2, realLevel)
                            stmt.setInt(3, score)
                            stmt.setString(4, cleanFeedback)
                            stmt.setString(5, transcript)

                            if (userId != null) {
                                stmt.setLong(6, userId)
                            } else {
                                stmt.setNull(6, java.sql.Types.BIGINT)
                            }

                            stmt.executeUpdate()
                        }
                    }
                    application.log.info("✅ Simulación registrada: Usuario $assistantId | Puntaje: $score")
                } else {
                    application.log.warn("⚠️ No se pudo extraer un puntaje válido del reporte de Vapi.")
                }
            }
            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            application.log.error("❌ Error crítico en Webhook: ${e.message}", e)
            call.respond(HttpStatusCode.OK) // Respondemos 200 para evitar reintentos infinitos de Vapi
        }
    }
}