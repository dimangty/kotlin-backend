# Неделя 14. Ktor: явный серверный стек для сравнения

**Результат недели:** реализовать те же HTTP-концепции в Ktor и увидеть, что меняется фреймворк, а HTTP, SQL и транзакции остаются.

Неделя не про то, какой фреймворк лучше. Она про то, какая часть ваших знаний была знанием о Spring, а какая - знанием о серверной разработке. Всё, что переносится без изменений, вы действительно понимали.

## Spring и Ktor: что именно отличается

| Задача | Spring Boot (`Weak1`, `Weak9`) | Ktor (этот проект) |
|---|---|---|
| Регистрация endpoint | `@RestController` + `@GetMapping`, найдено сканированием | `routing { get("/health") { ... } }` - явный вызов |
| Wiring зависимостей | контейнер, автоконфигурация | вы передаёте зависимости руками |
| Сериализация | Jackson подключается сам | `install(ContentNegotiation) { json() }` |
| Ошибки -> HTTP-коды | `@RestControllerAdvice` | `install(StatusPages)` |
| Аутентификация | filter chain, `SecurityContext` | `install(Authentication) { bearer(...) }` + `authenticate("auth") { ... }` |
| Тесты | `@SpringBootTest` + MockMvc | `testApplication { application { module() } }` |
| Что происходит при старте | нужно уметь читать автоконфигурацию | видно построчно в `module()` |

Ktor дешевле объяснить и дороже собрать: всё, что Spring делает по соглашению, здесь нужно написать. Обратная сторона - в [Application.kt](src/main/kotlin/study/week14/Application.kt) нет ни одной строки, которую нельзя проследить глазами.

## Теория и где она в проекте

| Тема | Где смотреть |
|---|---|
| Routing и вложенность | блок `routing` в `module()` |
| Plugins как pipeline | `install(...)` в порядке применения |
| Сериализация DTO | `@Serializable data class Echo` - kotlinx.serialization, генерация на этапе компиляции, без reflection |
| Единая обработка ошибок | `StatusPages` переводит `InvalidRequest` в `400` с телом `{"code","message"}` |
| Bearer authentication | учебный проверяющий; замена на JWT verifier не меняет routing |
| Конфигурация | [application.yaml](src/main/resources/application.yaml) - порт и список модулей, точка входа `EngineMain` |
| Тесты без поднятия сокета | [ApplicationTest.kt](src/test/kotlin/study/week14/ApplicationTest.kt) - `/health`, `400` на пустое сообщение, `401`/`401`/`200` для отсутствующего, неверного и верного токена |

## Запуск

```bash
./gradlew test
./gradlew run
```

```bash
curl -i localhost:8080/health
curl -i -H 'Content-Type: application/json' -d '{"message":"hi"}' localhost:8080/echo
curl -i -H 'Content-Type: application/json' -d '{"message":" "}' localhost:8080/echo   # 400
curl -i localhost:8080/payments/42                                                     # 401
curl -i -H 'Authorization: Bearer study-token' localhost:8080/payments/42              # 200
```

Bearer token намеренно учебный: `study-token` захардкожен. Криптография - не тема этой недели, а строка проверки заменяется на JWT verifier без изменения маршрутов (задание 1).

## Задания

1. **Настоящий JWT.** Подключить `ktor-server-auth-jwt`, проверять подпись, `exp` и `iss`, класть `userId` в principal. Убедиться, что блок `routing` не изменился ни на строку - это и есть проверка того, что аутентификация отделена от маршрутов.
2. **Перенести auth-контракт недели 9.** `register`, `login`, `refresh` с той же семантикой ответов (`201`, `200`, `401`) и теми же негативными тестами. Сравнить объём кода со Spring-версией.
3. **Ownership.** Добавить проверку владельца ресурса и `403`. Показать тестом, что чужой principal получает `403`, а не `404` и не `200`.
4. **Полный error contract.** Довести `StatusPages` до формата недели 2 (`code`, `message`, `details`, `requestId`), включая `404` на неизвестный маршрут и `400` на нечитаемый JSON.
5. **Валидация.** В Ktor нет Bean Validation. Реализовать проверку входных DTO явно и решить, где ей место: в маршруте, в отдельном слое или в конструкторе DTO.
6. **Таблица сравнения.** Заполнить по своему опыту: startup time, объём кода на endpoint, прозрачность потока выполнения, тестируемость, стоимость поиска «кто это сделал за меня». Эта таблица - обязательный артефакт недели 15.

## Что разобрать с ментором

- Различия без спора «какой фреймворк лучше»: для какой команды и какой задачи что выгоднее.
- Какие знания оказались полностью переносимыми, а какие были знанием об аннотациях Spring.
- Где в Ktor легко ошибиться именно потому, что фреймворк ничего не делает по умолчанию.

## Критерий готовности

- Можешь реализовать endpoint без аннотаций Spring.
- Можешь объяснить, что происходит при старте приложения, построчно.
- Транзакционная корректность не изменилась после смены фреймворка (проверяется на неделе 15).

## Контрольные вопросы

- Чем `install(plugin)` отличается от автоконфигурации Spring по последствиям, а не по синтаксису?
- Почему kotlinx.serialization не нужен reflection и что это даёт?
- Где в Ktor находится эквивалент `@RestControllerAdvice`?
- Что в этом проекте пришлось написать руками из того, что Spring делает сам, и что из этого вы бы хотели контролировать сами?

## Материалы

- [Ktor: Creating a server](https://ktor.io/docs/server-create-a-new-project.html)
- [Ktor: Routing](https://ktor.io/docs/server-routing.html)
- [Ktor: StatusPages](https://ktor.io/docs/server-status-pages.html)
- [Ktor: Authentication](https://ktor.io/docs/server-auth.html)
- [Ktor: Testing](https://ktor.io/docs/server-testing.html)
