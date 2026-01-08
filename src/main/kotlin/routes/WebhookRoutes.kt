package com.ff.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.DriverManager

@Serializable data class VapiWebhookEvent(val message: VapiMessagePayload)
@Serializable data class VapiMessagePayload(val type: String, val artifact: VapiArtifact? = null, val call: VapiCall? = null)
@Serializable data class VapiCall(val assistantId: String? = null)
@Serializable data class VapiArtifact(val transcript: String? = null)

fun Route.webhookRoutes() {
    post("/vapi/webhook") {
        try {
            // 1. LEER TEXTO CRUDO
            val bodyText = call.receiveText()

            // 2. FILTRO: Solo procesamos el reporte final
            if (bodyText.contains("end-of-call-report")) {

                val jsonParser = Json { ignoreUnknownKeys = true }
                val event = jsonParser.decodeFromString<VapiWebhookEvent>(bodyText)

                val transcript = event.message.artifact?.transcript ?: ""
                val assistantId = event.message.call?.assistantId

                println("\n🕵️ --- REPORTE RECIBIDO ---")

                // --- 3. LÓGICA DE EXTRACCIÓN ---
                var scoreRegex = Regex("""(?:PUNTAJE|NOTA|SCORE)[^\d]{0,10}(\d{1,3})""", RegexOption.IGNORE_CASE)
                var match = scoreRegex.find(transcript)

                if (match == null) {
                    scoreRegex = Regex("""\b(\d{1,3})\b[\s\S]{0,20}(?:Feedback|Comentario)""", RegexOption.IGNORE_CASE)
                    match = scoreRegex.find(transcript)
                }

                if (match != null) {
                    val score = match.groupValues[1].toInt()

                    // 4. LIMPIEZA QUIRÚRGICA DEL FEEDBACK
                    val endOfScoreIndex = match.range.last + 1
                    var rawFeedback = transcript.substring(endOfScoreIndex).trim()
                    val cleanupRegex = Regex("""(?s)^[\.,\-\s\n\r]*(?:Feedback|Comentario|FEEDBACK|Analysis)[:\.\s,\n\r]*""")
                    var cleanFeedback = rawFeedback.replaceFirst(cleanupRegex, "").trim()
                    cleanFeedback = cleanFeedback.trimStart(',', '.', '-', ':', ' ', '\n', '\r')

                    if (cleanFeedback.contains("User:", ignoreCase = true)) {
                        cleanFeedback = cleanFeedback.substringBefore("User:")
                    }
                    cleanFeedback = cleanFeedback.replace("AI:", "", ignoreCase = true).trim()

                    if (cleanFeedback.isNotEmpty()) {
                        cleanFeedback = cleanFeedback.replaceFirstChar { it.uppercase() }
                    } else {
                        cleanFeedback = "Sin feedback detallado."
                    }

                    // 5. GUARDAR EN DB CON DUEÑO (USER_ID)
                    val dbUrl = "jdbc:mysql://localhost:3306/nexos?allowPublicKeyRetrieval=true&useSSL=false"
                    val conn = DriverManager.getConnection(dbUrl, "root", "admin")

                    var realTopic = "Entrevista Técnica"
                    var realLevel = "Junior"
                    var userId: Long? = null // <--- NUEVO

                    if (assistantId != null) {
                        try {
                            // Buscamos topic, level Y EL USER_ID usando el assistant_id
                            val query = "SELECT user_id, topic, level FROM interviews WHERE assistant_id = ? LIMIT 1"
                            val stmt = conn.prepareStatement(query)
                            stmt.setString(1, assistantId)
                            val rs = stmt.executeQuery()

                            if (rs.next()) {
                                userId = rs.getLong("user_id") // <--- CAPTURAMOS EL ID
                                realTopic = rs.getString("topic")
                                realLevel = rs.getString("level") ?: "Junior"
                            }
                        } catch (e: Exception) { println("Error buscando metadata: ${e.message}") }
                    }

                    // INSERTAMOS INCLUYENDO EL USER_ID
                    val insertSQL = "INSERT INTO interview_results (topic, level, score, feedback, transcript, user_id) VALUES (?, ?, ?, ?, ?, ?)"
                    conn.prepareStatement(insertSQL).apply {
                        setString(1, realTopic)
                        setString(2, realLevel)
                        setInt(3, score)
                        setString(4, cleanFeedback)
                        setString(5, transcript)
                        // Si tenemos ID lo guardamos, sino NULL
                        if (userId != null) setLong(6, userId) else setNull(6, java.sql.Types.BIGINT)
                        executeUpdate()
                    }
                    conn.close()
                    println("✅ Guardado para User ID $userId: $score/100 en $realTopic")
                } else {
                    println("⚠️ No encontré el puntaje en: '$transcript'")
                }
            }
            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            println("❌ Error webhook: ${e.message}")
            call.respond(HttpStatusCode.OK)
        }
    }
}