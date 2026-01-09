package com.ff.dtos

import kotlinx.serialization.Serializable

/**
 * DTOs para la gestión de usuarios.
 * Separan la capa de presentación (JSON) de la capa de datos (SQL).
 */

@Serializable
data class CreateUserRequest(
    val firebaseUid: String,
    val email: String,
    val fullName: String
)

@Serializable
data class CreateUserResponse(
    val id: Long,
    val message: String
)

// 👇 ESTA FALTABA: Necesaria para recibir los datos de actualización de perfil
@Serializable
data class UpdateUserRequest(
    val fullName: String,
    val photoUrl: String? = null // Opcional: Solo viene si se subió una foto nueva
)