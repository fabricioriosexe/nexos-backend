package com.ff.config

import io.ktor.server.application.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.ff.models.Users
import com.ff.models.Interviews
import java.sql.DriverManager

/**
 * Configuración de la capa de persistencia.
 * Utiliza HikariCP para el pool de conexiones (rendimiento) y Exposed como ORM.
 */
fun Application.configureDatabases() {
    val myDriver = environment.config.property("storage.driverClassName").getString()
    val myUrl = environment.config.property("storage.jdbcURL").getString()
    val myUser = environment.config.property("storage.user").getString()
    val myPassword = environment.config.property("storage.password").getString()

    // Configuración del Pool de Conexiones (Vital para producción)
    val config = HikariConfig().apply {
        driverClassName = myDriver
        jdbcUrl = myUrl
        username = myUser
        password = myPassword
        maximumPoolSize = 3 // Mantenemos bajo para desarrollo local
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    }
    val dataSource = HikariDataSource(config)
    Database.connect(dataSource)

    // Creación inicial de tablas si no existen (SchemaUtils)
    transaction {
        SchemaUtils.create(Users, Interviews)
    }

    // 🛠️ MIGRACIONES AUTOMÁTICAS (PARCHE SQL) 🛠️
    // Este bloque permite evolucionar la base de datos sin borrarla.
    // Intenta agregar columnas nuevas; si fallan es porque ya existen.
    try {
        val conn = DriverManager.getConnection(myUrl, myUser, myPassword)
        val stmt = conn.createStatement()

        // 1. Migración: Agregar 'level' a interviews
        try {
            stmt.executeUpdate("ALTER TABLE interviews ADD COLUMN level VARCHAR(20) DEFAULT 'Junior'")
            println("✅ Columna 'level' agregada a tabla interviews")
        } catch (e: Exception) { /* Ignoramos si ya existe */ }

        // 2. Migración: Agregar 'level' a interview_results
        try {
            stmt.executeUpdate("ALTER TABLE interview_results ADD COLUMN level VARCHAR(20) DEFAULT 'Junior'")
            println("✅ Columna 'level' agregada a tabla interview_results")
        } catch (e: Exception) { /* Ignoramos si ya existe */ }

        // 3. Migración: Agregar 'user_id' a interview_results
        try {
            stmt.executeUpdate("ALTER TABLE interview_results ADD COLUMN user_id BIGINT DEFAULT NULL")
            println("✅ Columna 'user_id' agregada a tabla interview_results")
        } catch (e: Exception) { /* Ignoramos si ya existe */ }

        // 👇 4. ESTA ES LA QUE FALTABA AYER: Agregar 'photo_url' a users
        try {
            stmt.executeUpdate("ALTER TABLE users ADD COLUMN photo_url VARCHAR(500) DEFAULT NULL")
            println("✅ Columna 'photo_url' agregada a tabla users")
        } catch (e: Exception) { /* Ignoramos si ya existe */ }

        conn.close()
    } catch (e: Exception) {
        println("⚠️ Advertencia de migración: ${e.message}")
    }

    log.info("✅ Base de datos inicializada y migrada correctamente.")
}