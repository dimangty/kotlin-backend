plugins {
    // Kotlin/JVM используется для REST API и прозрачного JDBC-кода лабораторий.
    kotlin("jvm") version "2.3.10"
    // Spring-плагин открывает классы, которые фреймворк проксирует во время выполнения.
    kotlin("plugin.spring") version "2.3.10"
    // Версия Spring Boot задана пользователем и подтверждена официальной документацией.
    id("org.springframework.boot") version "4.1.0"
    // Плагин применяет согласованный BOM всех библиотек экосистемы Spring.
    id("io.spring.dependency-management") version "1.1.7"
}

group = "ru.dbappweb"
version = "1.0.0"

java {
    // Java 21 является LTS и входит в Docker-образы сборки и запуска.
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // Spring MVC предоставляет компактный JSON REST API для Web- и Desktop-клиентов.
    implementation("org.springframework.boot:spring-boot-starter-web")
    // Actuator используется healthcheck-ом Docker Compose.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Jackson Kotlin корректно сериализует data class без ручных DTO-мапперов.
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Прямой JDBC намеренно оставляет видимыми snapshots, locks, SQLSTATE и границы транзакций.
    runtimeOnly("org.postgresql:postgresql")

    // Тестовый starter содержит JUnit Jupiter и Spring test utilities.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Kotlin assertions дают лаконичные сообщения контрактным и интеграционным тестам.
    testImplementation(kotlin("test"))
    // Драйвер нужен опциональному интеграционному тесту с настоящим PostgreSQL 18.
    testRuntimeOnly("org.postgresql:postgresql")
}

kotlin {
    compilerOptions {
        // Строгая интерпретация nullability-аннотаций Spring снижает число скрытых NPE.
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test>().configureEach {
    // Все тесты используют JUnit Platform; интеграционный прогон включается отдельным флагом.
    useJUnitPlatform()
    systemProperty("dbappweb.integration", System.getProperty("dbappweb.integration") ?: "false")
    listOf("dbappweb.url", "dbappweb.user", "dbappweb.password").forEach { propertyName ->
        System.getProperty(propertyName)?.let { systemProperty(propertyName, it) }
    }
}
