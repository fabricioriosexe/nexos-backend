package com.ff.dtos

import kotlinx.serialization.Serializable

/**
 * DTOs para el flujo de entrevistas.
 */

@Serializable
data class CreateInterviewRequest(
    val firebaseUid: String, // Identificador del usuario que solicita la entrevista
    val topic: String,       // Ej: "React", "Kotlin", "Java"
    val level: String = "Junior" // Nivel de dificultad deseado
)

@Serializable
data class InterviewResponse(
    val id: Long,
    val topic: String,
    val status: String,
    val date: String,
    val assistantId: String? = null, // ID para conectar con el widget de Vapi
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