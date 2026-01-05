package com.ff.vapi

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// --- 1. ESTRUCTURAS DE DATOS ---

@Serializable
data class VapiMessage(val role: String, val content: String)

@Serializable
data class ModelConfig(
    val provider: String = "openai",
    val model: String = "gpt-3.5-turbo",
    val messages: List<VapiMessage>,
    val temperature: Double = 0.7,
    val maxTokens: Int = 250
)

@Serializable
data class TranscriberConfig(
    val provider: String = "deepgram",
    val model: String = "nova-2",
    val language: String
)

@Serializable
data class VoiceConfig(val provider: String, val voiceId: String)

@Serializable
data class CreateAssistantRequest(
    val name: String,
    val model: ModelConfig,
    val voice: VoiceConfig,
    val transcriber: TranscriberConfig,
    val firstMessage: String?,
    val serverUrl: String? = null
    // ⚠️ IMPORTANTE: Sacamos 'analysis' para que no de error 400
)

@Serializable
data class VapiAssistantResponse(val id: String, val name: String? = null)

// --- 2. CLIENTE VAPI ---

class VapiClient(private val apiKey: String, private val baseUrl: String) {

    private val jsonConfig = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(jsonConfig) }
        expectSuccess = false // Para poder leer el error si falla
    }

    suspend fun createEphemeralAssistant(prompt: String, topic: String): String {
        println("🤖 VapiClient: Configurando Juez VELOZ (1 Pregunta) para: $topic")

        // 👇 TU URL DE NGROK (La saqué de tu captura, chequeá que siga verde)
        val MI_URL_NGROK = "https://reunitable-zipppier-candie.ngrok-free.dev"
        val WEBHOOK_URL = "$MI_URL_NGROK/vapi/webhook"

        val isEnglish = topic.lowercase().let {
            it.contains("english") || it.contains("ingles")
        }

        val lang: String
        val voiceConfig: VoiceConfig
        val firstMsg: String
        val instructions: String

        if (isEnglish) {
            lang = "en"
            voiceConfig = VoiceConfig("azure", "en-US-AndrewNeural")
            firstMsg = "Hello! Ready for one technical question?"

            instructions = """
                Role: Technical Interviewer ($topic).
                Rules:
                1. Ask EXACTLY ONE technical question based on the topic.
                2. Wait for the user's answer.
                3. IMMEDIATELY after the answer, say "FINISH" and provide a score.
                4. REQUIRED FORMAT: "PUNTAJE: [0-100]. FEEDBACK: [Short summary]."
                5. Do not ask more questions. Say goodbye and stop speaking.
            """.trimIndent()
        } else {
            lang = "es"
            // 🇦🇷 Voz Tomás (Argentina)
            voiceConfig = VoiceConfig("azure", "es-AR-TomasNeural")
            firstMsg = "Hola. Te voy a hacer una sola pregunta técnica. ¿Listo?"

            // 🧠 INSTRUCCIONES DEL JUEZ VELOZ
            instructions = """
                Rol: Entrevistador Técnico ($topic).
                Reglas:
                1. Haz EXACTAMENTE UNA (1) pregunta técnica difícil sobre el tema.
                2. Espera que el usuario responda.
                3. INMEDIATAMENTE después de su respuesta, evalúa su desempeño.
                4. FORMATO OBLIGATORIO DE RESPUESTA FINAL:
                   "Muy bien, terminamos. PUNTAJE: [0 a 100]. FEEDBACK: [Resumen de 1 frase]."
                5. No hagas más preguntas. Despídete y corta.
            """.trimIndent()
        }

        val finalSystemPrompt = "$prompt\n\n$instructions"
        val systemMessage = VapiMessage(role = "system", content = finalSystemPrompt)

        val requestBody = CreateAssistantRequest(
            name = "Entrevistador $topic",
            model = ModelConfig(messages = listOf(systemMessage)),
            voice = voiceConfig,
            transcriber = TranscriberConfig("deepgram", "nova-2", lang),
            firstMessage = firstMsg,
            serverUrl = WEBHOOK_URL // El buzón sigue activo
        )

        try {
            val response = client.post("$baseUrl/assistant") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            val responseText = response.bodyAsText()

            // Imprimimos respuesta cruda por si las dudas
            println("📩 VAPI RESPONSE: $responseText")

            if (response.status.value in 200..299) {
                val data = jsonConfig.decodeFromString<VapiAssistantResponse>(responseText)
                println("✅ Asistente JUEZ VELOZ creado. ID: ${data.id}")
                return data.id
            } else {
                println("❌ ERROR VAPI: $responseText")
                throw RuntimeException("Vapi Error: $responseText")
            }
        } catch (e: Exception) {
            println("❌ Excepción: ${e.message}")
            throw e
        }
    }
}