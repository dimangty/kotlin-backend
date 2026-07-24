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
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
tasks.withType<Test> { useJUnitPlatform() }
