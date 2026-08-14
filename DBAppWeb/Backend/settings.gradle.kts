// Spring Boot-плагины и Kotlin-плагины разрешаются из стандартных репозиториев.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Resolver делает Java 21 toolchain воспроизводимым на машинах, где установлен только JDK 17.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Библиотеки приложения и тестов разрешаются из Maven Central.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Бэкенд самостоятельный: Docker собирает только эту папку и не тянет Wasm toolchain.
rootProject.name = "DBAppWebBackend"
