import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    // Kotlin Multiplatform позволяет переиспользовать модели и Compose UI в браузере и JVM Desktop.
    kotlin("multiplatform") version "2.3.10"
    // Компилятор сериализации создаёт безопасные JSON-кодеки для ответов Spring Boot API.
    kotlin("plugin.serialization") version "2.3.10"
    // Compose Multiplatform 1.10.3 поддерживает браузерный Wasm и нативное desktop-окно на JVM.
    id("org.jetbrains.compose") version "1.10.3"
    // Для Kotlin 2.x Compose compiler подключается отдельным Kotlin-плагином той же версии.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
}

group = "ru.dbappweb"
version = "1.0.0"

kotlin {
    // Именованный JVM-таргет отделяет desktop-код от самостоятельного Spring Boot-проекта.
    jvm("desktop")

    // Wasm/JS даёт полноценный Compose Canvas UI в современном браузере.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                // Фиксированное имя облегчает диагностику вкладки Network в браузере.
                outputFileName = "dbappweb.js"
                // Порт 8081 не конфликтует с REST API Spring Boot на 8080.
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    port = 8081
                    open = false
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            // UI полностью общий и не содержит браузерных или JDBC-классов.
            implementation("org.jetbrains.compose.runtime:runtime:1.10.3")
            implementation("org.jetbrains.compose.foundation:foundation:1.10.3")
            implementation("org.jetbrains.compose.material3:material3:1.10.0-alpha05")
            implementation("org.jetbrains.compose.ui:ui:1.10.3")
            // Корутины не блокируют Compose-поток во время HTTP-запросов к лаборатории.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            // Ktor и kotlinx.serialization дают типизированный REST-клиент без ручного разбора JSON.
            implementation("io.ktor:ktor-client-core:3.3.3")
            implementation("io.ktor:ktor-client-content-negotiation:3.3.3")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")
        }

        commonTest.dependencies {
            // Общий тест защищает состав шести экранов и уникальность 44 кнопок.
            implementation(kotlin("test"))
            // MockEngine проверяет REST-контракт одинаково для браузерной и desktop-сборки без живого сервера.
            implementation("io.ktor:ktor-client-mock:3.3.3")
            // runTest предоставляет детерминированную корутинную среду для suspend-методов API.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }

        wasmJsMain.dependencies {
            // JS/Wasm HTTP-движок использует браузерный Fetch API.
            implementation("io.ktor:ktor-client-js:3.3.3")
        }

        named("desktopMain") {
            dependencies {
                // currentOs подбирает Skiko и системную оконную реализацию для macOS, Windows или Linux.
                implementation(compose.desktop.currentOs)
                // CIO выполняет неблокирующие HTTP-запросы из JVM-приложения к локальному Spring Boot API.
                implementation("io.ktor:ktor-client-cio:3.3.3")
                // Simple-провайдер обслуживает SLF4J Ktor и убирает предупреждение при запуске готового .app.
                runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
            }
        }
    }
}

// Compose Desktop создаёт запускаемое приложение и установочные пакеты текущей операционной системы.
compose.desktop {
    application {
        mainClass = "ru.dbappweb.MainKt"

        nativeDistributions {
            // Gradle выберет только поддерживаемый текущей ОС формат во время соответствующей package-задачи.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "DBAppWeb"
            packageVersion = "1.0.0"
            description = "Учебные лаборатории PostgreSQL по транзакциям, блокировкам и индексам"
            vendor = "DBAppWeb"
        }
    }
}

/**
 * Клиент и самостоятельный Backend хранят копии каталога, поэтому сборка сравнивает их порядок и id.
 * Такая проверка не даёт добавить кнопку только на одной стороне REST-границы.
 */
val verifyCatalogParity by tasks.registering {
    group = "verification"
    description = "Проверяет совпадение идентификаторов примеров клиента и бэкенда"

    val frontendCatalog = layout.projectDirectory.file(
        "src/commonMain/kotlin/ru/dbappweb/model/DemoCatalog.kt",
    ).asFile
    val backendCatalog = layout.projectDirectory.file(
        "Backend/src/main/kotlin/ru/dbapp/model/DemoCatalog.kt",
    ).asFile
    inputs.files(frontendCatalog, backendCatalog)

    doLast {
        val examplePattern = Regex("""example\("([^"]+)""")
        fun ids(file: File): List<String> = examplePattern.findAll(file.readText()).map { it.groupValues[1] }.toList()

        val frontendIds = ids(frontendCatalog)
        val backendIds = ids(backendCatalog)
        check(frontendIds == backendIds) {
            "Каталоги клиента и бэкенда расходятся:\nclient=$frontendIds\nbackend=$backendIds"
        }
    }
}

tasks.named("allTests") {
    // Обычный тестовый прогон одновременно проверяет контракт двух самостоятельных Gradle-проектов.
    dependsOn(verifyCatalogParity)
}
