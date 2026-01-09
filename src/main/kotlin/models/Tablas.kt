package com.ff.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

/**
 * Definición del esquema de Base de Datos usando Exposed (ORM).
 * Mapea las clases de Kotlin a tablas SQL.
 */

object Users : Table("users") {
    val id = long("id").autoIncrement()

    // Indexamos el UID de Firebase para búsquedas ultra-rápidas durante el login
    val firebaseUid = varchar("firebase_uid", 128).uniqueIndex()
    val email = varchar("email", 128).uniqueIndex()

    val fullName = varchar("full_name", 128)
    val role = varchar("role", 20).default("student")
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }

    // 👇 Campo vital para la integración con Cloudinary
    val photoUrl = varchar("photo_url", 500).nullable()

    override val primaryKey = PrimaryKey(id)
}

object Interviews : Table("interviews") {
    val id = long("id").autoIncrement()

    // Relación Foreign Key: Si se borra un usuario, se mantiene la integridad referencial
    val userId = reference("user_id", Users.id)

    val topic = varchar("topic", 50)

    // Nivel de dificultad seleccionado (Impacta en el prompt de Vapi)
    val level = varchar("level", 20).default("Junior")

    // ID externo provisto por Vapi para trazar la llamada
    val assistantId = varchar("assistant_id", 100).nullable()

    val status = varchar("status", 20).default("IN_PROGRESS")
    val score = integer("score").nullable()
    val feedbackSummary = text("feedback_summary").nullable()
    val durationSeconds = integer("duration").default(0)
    val date = datetime("date").clientDefault { LocalDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}