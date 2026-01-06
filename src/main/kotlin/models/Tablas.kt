package com.ff.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

object Users : Table("users") {
    val id = long("id").autoIncrement()
    val firebaseUid = varchar("firebase_uid", 128).uniqueIndex()
    val email = varchar("email", 128).uniqueIndex()
    val fullName = varchar("full_name", 128)
    val role = varchar("role", 20).default("student")
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}

object Interviews : Table("interviews") {
    val id = long("id").autoIncrement()
    val userId = reference("user_id", Users.id)
    val topic = varchar("topic", 50)

    // 👇 NUEVO CAMPO: Guardamos el nivel (Trainee, Junior, Senior)
    val level = varchar("level", 20).default("Junior")

    val assistantId = varchar("assistant_id", 100).nullable()
    val status = varchar("status", 20).default("IN_PROGRESS")
    val score = integer("score").nullable()
    val feedbackSummary = text("feedback_summary").nullable()
    val durationSeconds = integer("duration").default(0)
    val date = datetime("date").clientDefault { LocalDateTime.now() }

    override val primaryKey = PrimaryKey(id)
}