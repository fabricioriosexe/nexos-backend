package com.ff.dtos

import kotlinx.serialization.Serializable

@Serializable
data class CreateInterviewRequest(
    val firebaseUid: String,
    val topic: String,
    val level: String = "Junior",
    // 👇 NUEVOS CAMPOS NIVEL 1
    val language: String = "es", // "es" o "en"
    val targetFocus: String? = null // Ej: "Mercado Libre", "Google"
)

@Serializable
data class InterviewResponse(
    val id: Long,
    val topic: String,
    val status: String,
    val date: String,
    val assistantId: String? = null,
    val imageUrl: String? = null
)

@Serializable
data class InterviewResultResponse(
    val id: Int,
    val score: Int,
    val feedback: String,
    val topic: String,
    val level: String = "Junior",
    val date: String
)


// DTOs para el Botón de Pánico (Copiloto)
@Serializable
data class CopilotRequest(val question: String, val topic: String)
@Serializable
data class CopilotResponse(val hint: String)