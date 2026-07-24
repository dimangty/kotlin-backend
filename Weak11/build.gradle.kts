plugins {
    kotlin("jvm") version "2.3.21"
    application
}
group = "study.backend"
version = "0.1.0"
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
repositories { mavenCentral() }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
application { mainClass = "study.week11.ApplicationKt" }
tasks.test { useJUnitPlatform() }
