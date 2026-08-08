// Репозитории плагинов перечислены явно, чтобы проект одинаково открывался из IDE и терминала.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Зависимости приложения берутся только из стандартных репозиториев.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// Имя отображается в Gradle и в окне импорта проекта IntelliJ IDEA.
rootProject.name = "DBApp"
