package com.ff.config

import io.ktor.server.application.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.ff.models.Users
import com.ff.models.Interviews
import java.sql.DriverManager // Necesario para el parche manual

fun Application.configureDatabases() {
    val myDriver = environment.config.property("storage.driverClassName").getString()
    val myUrl = environment.config.property("storage.jdbcURL").getString()
    val myUser = environment.config.property("storage.user").getString()
    val myPassword = environment.config.property("storage.password").getString()

    val config = HikariConfig().apply {
        driverClassName = myDriver
        jdbcUrl = myUrl
        username = myUser
        password = myPassword
        maximumPoolSize = 3
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    }
    val dataSource = HikariDataSource(config)
    Database.connect(dataSource)

    transaction {
        SchemaUtils.create(Users, Interviews)
    }

    // 🛠️ PARCHE SQL AUTOMÁTICO PARA AGREGAR 'LEVEL' 🛠️
    // Esto agrega las columnas si no existen.
    try {
        val conn = DriverManager.getConnection(myUrl, myUser, myPassword)
        val stmt = conn.createStatement()

        // 1. Agregar level a la tabla 'interviews' (Gestión interna)
        try {
            stmt.executeUpdate("ALTER TABLE interviews ADD COLUMN level VARCHAR(20) DEFAULT 'Junior'")
            println("✅ Columna 'level' agregada a tabla interviews")
        } catch (e: Exception) { /* Ignoramos si ya existe */ }

        // 2. Agregar level a la tabla 'interview_results' (Resultados finales)
        try {
            stmt.executeUpdate("ALTER TABLE interview_results ADD COLUMN level VARCHAR(20) DEFAULT 'Junior'")
            println("✅ Columna 'level' agregada a tabla interview_results")
        } catch (e: Exception) { /* Ignoramos si ya existe */ }

        conn.close()
    } catch (e: Exception) {
        println("⚠️ Advertencia de migración: ${e.message}")
    }

    log.info("✅ Base de datos lista con soporte de Niveles")
}