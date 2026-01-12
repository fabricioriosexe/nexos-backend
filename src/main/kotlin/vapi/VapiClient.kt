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

// ==========================================
// 1. ESTRUCTURAS DE DATOS (DTOs) PARA VAPI
// ==========================================

@Serializable
data class VapiMessage(
    val role: String,
    val content: String
)

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
data class VoiceConfig(
    val provider: String,
    val voiceId: String
)

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
data class VapiAssistantResponse(
    val id: String,
    val name: String? = null
)

// ==========================================
// 2. CLIENTE VAPI (LÓGICA PRINCIPAL)
// ==========================================

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

    /**
     * Crea un asistente efímero.
     * Recibe los parámetros nuevos: language y targetFocus para personalizar la experiencia.
     */
    suspend fun createEphemeralAssistant(
        promptBase: String,
        topic: String,
        language: String,
        targetFocus: String?
    ): String {
        println("🤖 VapiClient: Configurando IA para: $topic | Idioma: $language | Empresa: $targetFocus")

        // 👇 IMPORTANTE: Esta URL debe ser la que te da ngrok.exe cuando lo inicias
        val MI_URL_NGROK = "https://reunitable-zipppier-candie.ngrok-free.dev"
        val WEBHOOK_URL = "$MI_URL_NGROK/vapi/webhook"

        val voiceConfig: VoiceConfig
        val firstMsg: String
        val instructions: String

        // 1. Lógica de Empresa Objetivo (Si el usuario escribió algo, lo agregamos al prompt)
        val focusText = if (!targetFocus.isNullOrBlank()) {
            if (language == "en") "IMPORTANT CONTEXT: You are interviewing a candidate specifically for a position at '$targetFocus'. Adapt your tone accordingly."
            else "CONTEXTO IMPORTANTE: Estás entrevistando a un candidato específicamente para una posición en '$targetFocus'. Adapta tu tono."
        } else {
            ""
        }

        // 2. Configuración según Idioma
        if (language == "en") {
            // --- CONFIGURACIÓN INGLÉS ---
            voiceConfig = VoiceConfig("azure", "en-US-AndrewNeural")
            firstMsg = "Hello! Ready for your technical question?"

            // Prompt Anti-Alucinaciones (Inglés)
            instructions = """
                Role: Technical Interviewer ($topic). $focusText
                Rules:
                1. Ask EXACTLY ONE technical question relevant to $topic.
                2. WAITING PHASE: Wait for the candidate to explain their answer. Do NOT evaluate the initial greeting (e.g., "Yes", "Hello", "Ready").
                3. IMMEDIATE EVALUATION (Only after technical answer):
                   - Good answer -> High score.
                   - "I don't know" or irrelevant topic -> SCORE: 0.
                4. REQUIRED FORMAT: "SCORE: [0-100]. FEEDBACK: [Summary]."
                5. Do not ask more questions. Say goodbye.
            """.trimIndent()

        } else {
            // --- CONFIGURACIÓN ESPAÑOL ---
            voiceConfig = VoiceConfig("azure", "es-AR-TomasNeural")
            firstMsg = "Hola. Te voy a hacer una sola pregunta técnica. ¿Listo?"

            // Prompt Anti-Alucinaciones (Español)
            instructions = """
                Rol: Entrevistador Técnico ($topic). $focusText
                
                Reglas ESTRICTAS:
                1. Haz UNA (1) pregunta técnica sobre $topic.
                2. FASE DE ESPERA: Espera a que el candidato explique su solución técnica. NO EVALÚES el saludo inicial (ej: "Hola", "Sí", "Listo").
                3. EVALUACIÓN (Solo tras la respuesta técnica):
                   - Si responde bien -> Puntaje alto.
                   - Si responde "no sé", hay silencio total o habla de OTRO TEMA -> PUNTAJE: 0.
                
                4. FORMATO OBLIGATORIO:
                   "PUNTAJE: [0-100]. FEEDBACK: [Tu opinión]."
                   
                5. Despídete y corta.
            """.trimIndent()
        }

        // 3. Construcción del Prompt Final
        val finalSystemPrompt = "$promptBase\n\n$instructions"
        val systemMessage = VapiMessage(role = "system", content = finalSystemPrompt)

        // 4. Armado del Request (Usando el idioma correcto en el Transcriber)
        val requestBody = CreateAssistantRequest(
            name = "Entrevistador $topic ($language)",
            model = ModelConfig(messages = listOf(systemMessage)),
            voice = voiceConfig,
            transcriber = TranscriberConfig("deepgram", "nova-2", language), // Importante para que entienda tu acento
            firstMessage = firstMsg,
            serverUrl = WEBHOOK_URL
        )

        // 5. Llamada a la API de Vapi
        try {
            val response = client.post("$baseUrl/assistant") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
            val responseText = response.bodyAsText()

            // Logs detallados para debugging
            println("📩 VAPI RESPONSE RAW: $responseText")

            if (response.status.value in 200..299) {
                val data = jsonConfig.decodeFromString<VapiAssistantResponse>(responseText)
                println("✅ Asistente creado exitosamente. ID: ${data.id}")
                return data.id
            } else {
                println("❌ ERROR VAPI STATUS: ${response.status}")
                throw RuntimeException("Vapi Error: $responseText")
            }
        } catch (e: Exception) {
            println("❌ Excepción crítica en VapiClient: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
}