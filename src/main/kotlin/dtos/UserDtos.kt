package com.ff.dtos

import kotlinx.serialization.Serializable

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