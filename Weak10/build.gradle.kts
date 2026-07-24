plugins { kotlin("jvm") version "2.3.21" }
group = "study.backend"
version = "0.1.0"
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
repositories { mavenCentral() }
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.2")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.postgresql:postgresql:42.7.5")
}
tasks.test {
    useJUnitPlatform()
    // Docker Desktop on macOS exposes a user socket when /var/run/docker.sock is not installed.
    val desktopSocket = file("${System.getProperty("user.home")}/.docker/run/docker.sock")
    if (desktopSocket.exists() && System.getenv("DOCKER_HOST") == null) {
        environment("DOCKER_HOST", "unix://${desktopSocket.absolutePath}")
    }
}
