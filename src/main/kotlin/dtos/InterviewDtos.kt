package com.ff.dtos

import kotlinx.serialization.Serializable

@Serializable
data class CreateInterviewRequest(
    val firebaseUid: String,
    val topic: String,
    val level: String = "Junior" // 👈 NUEVO: Campo de nivel con valor por defecto
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
    val level: String = "Junior", // 👈 NUEVO: Mandamos el nivel al frontend
    val date: String
)
