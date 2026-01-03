package com.ff.dtos

import kotlinx.serialization.Serializable

@Serializable
data class CreateInterviewRequest(
    val firebaseUid: String,
    val topic: String
)

@Serializable
data class InterviewResponse(
    val id: Long,
    val topic: String,
    val status: String,
    val date: String,
    val assistantId: String? = null, // ID para conectar la llamada
    val imageUrl: String? = null     // URL para el diseño de tarjetas
)


@Serializable
data class CreateUserResponse(val id: Long, val message: String)