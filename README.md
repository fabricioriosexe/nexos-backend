# Nexos AI - Backend de Entrevistas Inteligentes 🚀

Backend desarrollado en **Kotlin** con **Ktor** para la gestión de simulaciones de entrevistas técnicas utilizando **Inteligencia Artificial (Gemini 1.5 Flash)**.

## ✨ Características
- **Prompts Dinámicos**: Generación automática de perfiles de entrevistador según el tema (Técnico o Coach).
- **Gestión de Asistentes**: Integración con Vapi AI para asistentes de voz efímeros.
- **Persistencia**: Base de datos MySQL gestionada con Exposed ORM.
- **Validación Robusta**: Control de errores en entradas de usuario y respuestas de APIs externas.

## 🛠️ Tecnologías
- **Lenguaje:** Kotlin.
- **Framework:** Ktor.
- **Base de Datos:** MySQL con Exposed ORM.
- **IA:** Vapi SDK / Google Gemini.

## 📡 Endpoints Principales
- `POST /users`: Registro de usuarios vinculados a Firebase.
- `POST /interviews`: Creación de sesión de entrevista con IA.

## 🚀 Instalación
1. Renombra `src/main/resources/application.example.yaml` a `application.yaml`.
2. Configura tus credenciales de base de datos y Vapi API Key.
3. Ejecuta `./gradlew run`.