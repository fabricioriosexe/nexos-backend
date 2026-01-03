package com.ff.vapi

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// --- CLASES INTERNAS DE VAPI ---
@Serializable
data class ModelConfig(
    val provider: String = "google",
    val model: String = "gemini-1.5-flash",
    @SerialName("systemPrompt") val systemPrompt: String,
    val temperature: Double = 0.7
)

@Serializable
data class CreateAssistantRequest(
    val name: String,
    val model: ModelConfig,
    val firstMessage: String? = "Hola, soy tu entrevistador. ¿Comenzamos?"
)

@Serializable
data class VapiAssistantResponse(val id: String, val name: String? = null)

// --- CLIENTE PRINCIPAL ---
class VapiClient(private val apiKey: String, private val baseUrl: String) {

    // Configuración JSON tolerante a fallos
    private val jsonConfig = Json {
        prettyPrint = true
        ignoreUnknownKeys = true // Evita error por campos nuevos de Vapi (orgId)
        encodeDefaults = true    // Obliga a enviar "google" aunque sea default
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonConfig)
        }
    }

    suspend fun createEphemeralAssistant(prompt: String, topic: String): String {
        println("🤖 VapiClient: Contactando a Vapi para: $topic")

        // CORTE DE SEGURIDAD: Nombres de más de 40 chars rompen Vapi
        val rawName = "Entrevistador $topic"
        val safeName = if (rawName.length > 40) rawName.take(37) + "..." else rawName

        val requestBody = CreateAssistantRequest(
            name = safeName,
            model = ModelConfig(
                provider = "google",
                model = "gemini-1.5-flash",
                systemPrompt = prompt
            )
        )

        try {
            val response = client.post("$baseUrl/assistant") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            val responseText = response.bodyAsText()

            if (response.status.value in 200..299) {
                val data = jsonConfig.decodeFromString<VapiAssistantResponse>(responseText)
                println("✅ Asistente creado: $safeName (ID: ${data.id})")
                return data.id
            } else {
                println("❌ ERROR VAPI (${response.status}): $responseText")
                throw RuntimeException("Vapi Error: $responseText")
            }
        } catch (e: Exception) {
            println("❌ Excepción VapiClient: ${e.message}")
            throw e
        }
    }
}