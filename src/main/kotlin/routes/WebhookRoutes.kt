package com.ff.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// Estructuras simples
@Serializable data class VapiWebhookEvent(val message: VapiMessagePayload)
@Serializable data class VapiMessagePayload(val type: String, val artifact: VapiArtifact? = null)
@Serializable data class VapiArtifact(val transcript: String? = null, val recordingUrl: String? = null)

fun Route.webhookRoutes() {
    post("/vapi/webhook") {
        try {
            val event = call.receive<VapiWebhookEvent>()

            if (event.message.type == "end-of-call-report") {
                val transcript = event.message.artifact?.transcript ?: ""

                println("\n🕵️ --- ANALIZANDO ENTREVISTA ---")

                // 🔍 REGEX MEJORADO (A prueba de balas)
                // Acepta: "Puntaje: 80", "Puntaje, 80", "Puntaje 80", "Puntaje. 80"
                val scoreRegex = Regex("""PUNTAJE[^\d]{0,5}(\d{1,3})""", RegexOption.IGNORE_CASE)
                val match = scoreRegex.find(transcript)

                if (match != null) {
                    val score = match.groupValues[1]
                    println("🏆 ¡PUNTAJE ENCONTRADO!: $score / 100")
                    println("📝 Transcripción final:\n$transcript")

                    // TODO: PRÓXIMO PASO -> GUARDAR EN BASE DE DATOS
                } else {
                    println("⚠️ No encontré el puntaje. Transcripción: $transcript")
                }
                println("-------------------------------\n")
            }
            call.respond(HttpStatusCode.OK)
        } catch (e: Exception) {
            println("❌ Error Webhook: ${e.message}")
            call.respond(HttpStatusCode.OK)
        }
    }
}