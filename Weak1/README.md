# Неделя 1. Kotlin/JVM, Gradle и HTTP

Минимальный Spring Boot-сервис, на котором можно проследить путь `HTTP -> DispatcherServlet -> controller -> JSON`.

## Запуск

```bash
./gradlew bootRun
curl -i http://localhost:8080/health
curl -i 'http://localhost:8080/hello?name=Kotlin'
curl -i -H 'Content-Type: application/json' -H 'X-Request-Id: demo-1' -d '{"message":"hello"}' http://localhost:8080/echo
```

## Что изучить в коде

- `Application.kt`: точка входа, JAR и Spring lifecycle.
- `HttpController.kt`: method, query/header/body, status и cache header.
- `HttpControllerTest.kt`: HTTP-контракт без реального TCP-сокета.

## Задания

1. Добавить профиль `test` и отличающееся greeting-сообщение.
2. Добавить `HEAD /health` и проверить headers через `curl -I`.
3. Временно поставить `Thread.sleep(2_000)` и нагрузить endpoint, наблюдая потоки.

Готово, когда проект собирается без IDE и вы можете нарисовать lifecycle запроса.

