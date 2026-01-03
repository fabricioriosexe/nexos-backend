package com.ff.dtos

import kotlinx.serialization.Serializable

@Serializable
data class CreateUserRequest(
    val firebaseUid: String,
    val email: String,
    val fullName: String
)
