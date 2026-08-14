// Репозитории плагинов заданы явно, чтобы мультиплатформенный проект одинаково открывался из IDE и терминала.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Все зависимости клиента берутся из публичных репозиториев JetBrains и Maven Central.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// Бэкенд является самостоятельным Spring Boot-проектом и собирается своим wrapper внутри Backend.
rootProject.name = "DBAppWeb"
