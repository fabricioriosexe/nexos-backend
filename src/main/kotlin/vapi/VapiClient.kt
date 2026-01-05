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
        expectSuccess = false
    }

    suspend fun createEphemeralAssistant(prompt: String, topic: String): String {
        println("🤖 VapiClient: Configurando Juez VELOZ para: $topic")

        // 👇 TU URL DE NGROK (Asegurate de que sea la que generaste HOY)
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
                1. Ask EXACTLY ONE technical question.
                2. Wait for the answer.
                3. IMMEDIATE EVALUATION:
                   - Good answer -> High score.
                   - "I don't know" or irrelevant topic -> SCORE: 0.
                4. REQUIRED FORMAT: "PUNTAJE: [0-100]. FEEDBACK: [Summary]."
                5. Do not ask more questions. Say goodbye.
            """.trimIndent()
        } else {
            lang = "es"
            // 🇦🇷 Voz Tomás (Argentina)
            voiceConfig = VoiceConfig("azure", "es-AR-TomasNeural")
            firstMsg = "Hola. Te voy a hacer una sola pregunta técnica. ¿Listo?"

            // 🧠 PROMPT ANTI-TROLL Y ESTRICTO
            instructions = """
                Rol: Entrevistador Técnico ($topic).
                
                Reglas ESTRICTAS:
                1. Haz UNA (1) pregunta técnica sobre $topic.
                2. Espera la respuesta.
                3. EVALUACIÓN INMEDIATA:
                   - Si responde bien -> Puntaje alto.
                   - Si responde "no sé", se queda callado o habla de OTRO TEMA (fútbol, clima, chistes) -> PUNTAJE: 0.
                
                4. FORMATO OBLIGATORIO (No inventes otro):
                   "PUNTAJE: [0-100]. FEEDBACK: [Tu opinión]."
                   
                5. IMPORTANTE: Aunque el usuario te insulte o diga chistes, TU DEBER ES DAR EL PUNTAJE.
                6. Despídete y corta.
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
            serverUrl = WEBHOOK_URL
        )

        try {
            val response = client.post("$baseUrl/assistant") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            val responseText = response.bodyAsText()

            println("📩 VAPI RESPONSE: $responseText")

            if (response.status.value in 200..299) {
                val data = jsonConfig.decodeFromString<VapiAssistantResponse>(responseText)
                println("✅ Asistente creado. ID: ${data.id}")
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