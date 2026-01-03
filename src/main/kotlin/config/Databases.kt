package com.ff.config

import io.ktor.server.application.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction // <--- Importante para crear tablas
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

// IMPORTANTE: Importa tus tablas aquí
import com.ff.models.Users
import com.ff.models.Interviews

fun Application.configureDatabases() {
    // 1. Leemos la configuración
    val myDriver = environment.config.property("storage.driverClassName").getString()
    val myUrl = environment.config.property("storage.jdbcURL").getString()
    val myUser = environment.config.property("storage.user").getString()
    val myPassword = environment.config.property("storage.password").getString()

    // 2. Configuramos la conexión
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

    // 3. Conectamos
    val dataSource = HikariDataSource(config)
    Database.connect(dataSource)

    // 4. CREAMOS LAS TABLAS (Esto es lo que te faltaba)
    transaction {
        // Si no existen, crea las tablas Users e Interviews en MySQL
        SchemaUtils.create(Users, Interviews)
    }

    log.info("✅ Base de datos conectada y Tablas sincronizadas")
}