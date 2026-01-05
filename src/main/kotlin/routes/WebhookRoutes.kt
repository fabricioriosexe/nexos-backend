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

                // 1. BUSCAMOS EL PUNTAJE
                var scoreRegex = Regex("""(?:PUNTAJE|NOTA|SCORE)[^\d]{0,10}(\d{1,3})""", RegexOption.IGNORE_CASE)
                var match = scoreRegex.find(transcript)

                // Fallback si no hay etiqueta
                if (match == null) {
                    scoreRegex = Regex("""\b(\d{1,3})\b.*(?:Feedback|Comentario)""", RegexOption.IGNORE_CASE)
                    match = scoreRegex.find(transcript)
                }

                if (match != null) {
                    val score = match.groupValues[1].toInt()

                    // 2. CORTE QUIRÚRGICO (LA SOLUCIÓN) 🔪
                    // Tomamos el índice donde termina el número del puntaje
                    val endOfScoreIndex = match.range.last + 1

                    // Nos quedamos SOLO con lo que sigue después del número
                    var rawFeedback = transcript.substring(endOfScoreIndex).trim()

                    // 3. LIMPIEZA FINA
                    // Sacamos palabras como "Feedback:", "Comentario:", puntos, comas iniciales
                    val cleanupRegex = Regex("""^[\.,\-\s]*(?:Feedback|Comentario|FEEDBACK)[:\.\s]*""", RegexOption.IGNORE_CASE)
                    var cleanFeedback = rawFeedback.replaceFirst(cleanupRegex, "").trim()

                    // Si quedó basura de "AI:" o "User:" al final (raro, pero posible), lo sacamos
                    if (cleanFeedback.contains("User:", ignoreCase = true)) {
                        cleanFeedback = cleanFeedback.substringBefore("User:")
                    }
                    cleanFeedback = cleanFeedback.replace("AI:", "", ignoreCase = true).trim()

                    // Mayúscula inicial
                    if (cleanFeedback.isNotEmpty()) {
                        cleanFeedback = cleanFeedback.replaceFirstChar { it.uppercase() }
                    } else {
                        cleanFeedback = "Sin feedback detallado."
                    }

                    // 4. GUARDAR EN DB
                    val dbUrl = "jdbc:mysql://localhost:3306/nexos?allowPublicKeyRetrieval=true&useSSL=false"
                    val conn = DriverManager.getConnection(dbUrl, "root", "admin")

                    var realTopic = "Entrevista Técnica"
                    if (assistantId != null) {
                        try {
                            val rs = conn.prepareStatement("SELECT topic FROM interviews WHERE assistant_id = ? LIMIT 1").apply { setString(1, assistantId) }.executeQuery()
                            if (rs.next()) realTopic = rs.getString("topic")
                        } catch (e: Exception) {}
                    }

                    val insertSQL = "INSERT INTO interview_results (topic, score, feedback, transcript) VALUES (?, ?, ?, ?)"
                    conn.prepareStatement(insertSQL).apply {
                        setString(1, realTopic)
                        setInt(2, score)
                        setString(3, cleanFeedback) // Texto purificado
                        setString(4, transcript)    // Texto original
                        executeUpdate()
                    }
                    conn.close()
                    println("✅ Guardado: $score en $realTopic")
                    println("📝 Feedback Final Limpio: $cleanFeedback")
                }
            }
            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            println("❌ Error: ${e.message}")
            call.respond(HttpStatusCode.OK)
        }
    }
}