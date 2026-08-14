# syntax=docker/dockerfile:1

# Первый слой компилирует executable Spring Boot jar на LTS Java 21.
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

# Wrapper и build scripts копируются раньше исходников для повторного использования Docker layer cache.
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN chmod +x gradlew

# Исходники меняются чаще зависимостей и поэтому копируются последним отдельным слоем.
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon

# Финальный образ не содержит компилятор, Gradle и исходный код.
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S dbapp && adduser -S dbapp -G dbapp
WORKDIR /app
COPY --from=builder /workspace/build/libs/DBAppWebBackend-1.0.0.jar app.jar

USER dbapp
EXPOSE 8080

# Actuator сообщает Docker, что приложение не только запущено, но и отвечает по HTTP.
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
