# Build и runtime разделены: Gradle/JDK не попадают в итоговый image.
FROM gradle:8.14.3-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system app && useradd --system --create-home --home-dir /app --gid app app
USER app
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
