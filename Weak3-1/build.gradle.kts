plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "study.backend"
version = "0.1.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
kotlin { compilerOptions { freeCompilerArgs.add("-Xannotation-default-target=param-property") } }
repositories { mavenCentral() }
// Keep the entire Testcontainers family on the Docker Engine 29-compatible line.
extra["testcontainers.version"] = "2.0.5"

dependencies {
    implementation(kotlin("reflect"))
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation(enforcedPlatform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Docker Desktop for macOS exposes this socket outside the usual /var/run path.
    val desktopSocket = file("${System.getProperty("user.home")}/.docker/run/docker.sock")
    if (desktopSocket.exists() && System.getenv("DOCKER_HOST") == null) {
        environment("DOCKER_HOST", "unix://${desktopSocket.absolutePath}")
    }
}
