import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // Kotlin Multiplatform отделяет общую Compose-часть от JVM-доступа к PostgreSQL.
    kotlin("multiplatform") version "2.3.10"
    // Плагин Compose Multiplatform подключает UI-библиотеки и desktop-задачи.
    id("org.jetbrains.compose") version "1.10.3"
    // Начиная с Kotlin 2.x Compose-компилятор подключается отдельным Kotlin-плагином.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
}

group = "ru.dbapp"
version = "1.0.0"

kotlin {
    // Учебный стенд запускается как desktop-приложение и обращается к локальному PostgreSQL через JDBC.
    jvm("desktop")

    // Java 17 доступна в стандартной поставке IntelliJ IDEA и достаточна для выбранных библиотек.
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            // Общая часть содержит Compose UI, поэтому не зависит от java.sql.
            implementation("org.jetbrains.compose.runtime:runtime:1.10.3")
            implementation("org.jetbrains.compose.foundation:foundation:1.10.3")
            implementation("org.jetbrains.compose.material3:material3:1.10.0-alpha05")
            implementation("org.jetbrains.compose.ui:ui:1.10.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }

        commonTest.dependencies {
            // Общие тесты проверяют каталог экранов и примеров без запуска PostgreSQL.
            implementation(kotlin("test"))
        }

        val desktopMain by getting {
            dependencies {
                // currentOs выбирает нативные Compose-библиотеки для текущей macOS/Linux/Windows.
                implementation(compose.desktop.currentOs)
                // JDBC-драйвер совместим с PostgreSQL 18 и не требует ORM для наглядных SQL-лабораторий.
                implementation("org.postgresql:postgresql:42.7.11")
                // Swing-диспетчер предоставляет Dispatchers.Main desktop-приложению.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }

        val desktopTest by getting {
            dependencies {
                // JVM-тест сверяет каталог UI с реестром реально реализованных JDBC-сценариев.
                implementation(kotlin("test-junit5"))
            }
        }
    }
}

compose.desktop {
    application {
        // Точка входа создаёт окно и передаёт UI реализацию PostgreSQL-движка.
        mainClass = "ru.dbapp.MainKt"

        nativeDistributions {
            // Форматы пригодятся, если учебное приложение понадобится установить как обычную программу.
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
            packageName = "DBApp"
            packageVersion = "1.0.0"
            description = "Учебные лаборатории PostgreSQL: ACID, изоляция, блокировки и индексы"
        }
    }
}

tasks.withType<Test>().configureEach {
    // JUnit Platform нужен JVM-тестам Kotlin.
    useJUnitPlatform()
    // Интеграционный флаг передаётся в отдельный test JVM только при явном -Ddbapp.integration=true.
    systemProperty("dbapp.integration", System.getProperty("dbapp.integration") ?: "false")
    listOf("dbapp.url", "dbapp.user", "dbapp.password").forEach { propertyName ->
        System.getProperty(propertyName)?.let { systemProperty(propertyName, it) }
    }
}
