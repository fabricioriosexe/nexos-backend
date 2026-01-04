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
    // 🧠 CEREBRO: Usamos GPT-3.5 de OpenAI (Consume tus créditos de Vapi, es barato y estable)
    val provider: String = "openai",
    val model: String = "gpt-3.5-turbo",
    val messages: List<VapiMessage>,
    val temperature: Double = 0.7, // Creatividad balanceada
    val maxTokens: Int = 150       // Respuestas concisas
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
    val firstMessage: String?
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
    }

    suspend fun createEphemeralAssistant(prompt: String, topic: String): String {
        println("🤖 VapiClient: Configurando Entrevista para: $topic")

        // Detección simple de idioma
        val isEnglish = topic.lowercase().let {
            it.contains("english") || it.contains("ingles") || it.contains("inglés")
        }

        val lang: String
        val voiceConfig: VoiceConfig
        val firstMsg: String
        val instructions: String

        if (isEnglish) {
            // --- MODO INGLÉS ---
            lang = "en"
            // Voz: Andrew (Azure) - Clara y profesional
            voiceConfig = VoiceConfig(provider = "azure", voiceId = "en-US-AndrewNeural")
            firstMsg = "Hello! I am your technical interviewer. Ready to start?"

            instructions = """
                Role: Senior Technical Interviewer specialized in $topic.
                Goal: Assess the candidate's knowledge with 10 technical questions.
                Rules:
                1. Ask ONE question at a time. Wait for the answer.
                2. Keep your responses short and professional.
                3. Do not invent names or technologies. Stick to standard $topic concepts.
                4. After 10 questions, say "Interview Finished" and stop.
            """.trimIndent()

        } else {
            // --- MODO ESPAÑOL (ARGENTINA) ---
            lang = "es"

            // 🇦🇷 VOZ: TOMAS (Azure) - Acento Argentino/Rioplatense
            // Es mucho más amigable y se entiende perfecto.
            voiceConfig = VoiceConfig(provider = "azure", voiceId = "es-AR-TomasNeural")

            firstMsg = "Hola. Soy tu entrevistador técnico. ¿Estás listo para arrancar?"

            instructions = """
                Rol: Entrevistador Técnico Senior experto en $topic.
                Objetivo: Evaluar al candidato con una serie de 10 preguntas técnicas.
                Reglas:
                1. Haz UNA sola pregunta a la vez. Espera que el usuario responda.
                2. Sé amable pero profesional. Usa un tono natural.
                3. No inventes términos raros. Usa terminología estándar de la industria.
                4. Tus respuestas deben ser breves (máximo 2 oraciones) para agilizar la charla.
                5. Al llegar a la pregunta 10, di "Entrevista finalizada" y despídete.
            """.trimIndent()
        }

        // Armamos el mensaje de sistema (Prompt + Instrucciones)
        val finalSystemPrompt = "$prompt\n\n$instructions"
        val systemMessage = VapiMessage(role = "system", content = finalSystemPrompt)

        // Creamos el Request
        val requestBody = CreateAssistantRequest(
            name = "Entrevistador $topic",
            model = ModelConfig(
                messages = listOf(systemMessage)
            ),
            voice = voiceConfig,
            transcriber = TranscriberConfig(
                provider = "deepgram",
                model = "nova-2",
                language = lang
            ),
            firstMessage = firstMsg
        )

        // Enviamos a Vapi
        try {
            val response = client.post("$baseUrl/assistant") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            val responseText = response.bodyAsText()

            if (response.status.value in 200..299) {
                val data = jsonConfig.decodeFromString<VapiAssistantResponse>(responseText)
                println("✅ Asistente creado exitosamente. ID: ${data.id}")
                return data.id
            } else {
                println("❌ ERROR VAPI: $responseText")
                throw RuntimeException("Vapi Error: $responseText")
            }
        } catch (e: Exception) {
            println("❌ Excepción en conexión: ${e.message}")
            throw e
        }
    }
}