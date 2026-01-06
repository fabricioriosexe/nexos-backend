package com.ff.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.sql.DriverManager

@Serializable data class VapiWebhookEvent(val message: VapiMessagePayload)
@Serializable data class VapiMessagePayload(val type: String, val artifact: VapiArtifact? = null, val call: VapiCall? = null)
@Serializable data class VapiCall(val assistantId: String? = null)
@Serializable data class VapiArtifact(val transcript: String? = null)

fun Route.webhookRoutes() {
    post("/vapi/webhook") {
        try {
            val event = call.receive<VapiWebhookEvent>()
            if (event.message.type == "end-of-call-report") {
                val transcript = event.message.artifact?.transcript ?: ""
                val assistantId = event.message.call?.assistantId
                println("\n🕵️ --- ANALIZANDO ---")

                // 1. Regex de Puntaje
                var scoreRegex = Regex("""(?:PUNTAJE|NOTA|SCORE)[^\d]{0,10}(\d{1,3})""", RegexOption.IGNORE_CASE)
                var match = scoreRegex.find(transcript)
                if (match == null) {
                    scoreRegex = Regex("""\b(\d{1,3})\b.*(?:Feedback|Comentario)""", RegexOption.IGNORE_CASE)
                    match = scoreRegex.find(transcript)
                }

                if (match != null) {
                    val score = match.groupValues[1].toInt()

                    // 2. Limpieza de Texto (Anti-User)
                    val endOfScoreIndex = match.range.last + 1
                    var rawFeedback = transcript.substring(endOfScoreIndex).trim()

                    val cleanupRegex = Regex("""^[\.,\-\s]*(?:Feedback|Comentario|FEEDBACK)[:\.\s]*""", RegexOption.IGNORE_CASE)
                    var cleanFeedback = rawFeedback.replaceFirst(cleanupRegex, "").trim()

                    if (cleanFeedback.contains("User:", ignoreCase = true)) {
                        cleanFeedback = cleanFeedback.substringBefore("User:")
                    }
                    cleanFeedback = cleanFeedback.replace("AI:", "", ignoreCase = true).trim()

                    if (cleanFeedback.isNotEmpty()) {
                        cleanFeedback = cleanFeedback.replaceFirstChar { it.uppercase() }
                    } else {
                        cleanFeedback = "Sin feedback detallado."
                    }

                    // 3. GUARDAR EN DB CON LEVEL
                    val dbUrl = "jdbc:mysql://localhost:3306/nexos?allowPublicKeyRetrieval=true&useSSL=false"
                    val conn = DriverManager.getConnection(dbUrl, "root", "admin")

                    var realTopic = "Entrevista Técnica"
                    var realLevel = "Junior" // Default

                    if (assistantId != null) {
                        try {
                            // Recuperamos el level original usando assistant_id
                            val query = "SELECT topic, level FROM interviews WHERE assistant_id = ? LIMIT 1"
                            val stmt = conn.prepareStatement(query)
                            stmt.setString(1, assistantId)
                            val rs = stmt.executeQuery()

                            if (rs.next()) {
                                realTopic = rs.getString("topic")
                                realLevel = rs.getString("level") ?: "Junior"
                            }
                        } catch (e: Exception) {}
                    }

                    val insertSQL = "INSERT INTO interview_results (topic, level, score, feedback, transcript) VALUES (?, ?, ?, ?, ?)"
                    conn.prepareStatement(insertSQL).apply {
                        setString(1, realTopic)
                        setString(2, realLevel) // Guardamos el nivel
                        setInt(3, score)
                        setString(4, cleanFeedback)
                        setString(5, transcript)
                        executeUpdate()
                    }
                    conn.close()
                    println("✅ Guardado: $score en $realTopic ($realLevel)")
                }
            }
            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            println("❌ Error: ${e.message}")
            call.respond(HttpStatusCode.OK)
        }
    }
}