# Неделя 1. Kotlin/JVM на сервере, Gradle и HTTP

**Результат недели:** поднять первое Spring Boot-приложение и понимать жизненный цикл HTTP-запроса от сокета до метода контроллера и обратно.

Проект намеренно минимальный: три endpoint и один тест. Внимание уходит не на код, а на то, что происходит вокруг него - как собирается JAR, кто создаёт бины, какой поток обслуживает запрос и что случится, если этот поток заблокировать.

## Отличие сервера от мобильного приложения

Это главный сдвиг мышления недели, и он не про синтаксис:

| | iOS / KMP клиент | Backend-процесс |
|---|---|---|
| Время жизни | сессия пользователя | недели, до следующего деплоя |
| Параллелизм | один пользователь, главный поток UI | сотни одновременных запросов, UI-потока нет |
| Состояние | принадлежит пользователю | общее, конкурентное, разделяется между запросами |
| Утечка памяти | съедает память одного устройства | копится, пока процесс не упадёт под нагрузкой |
| Блокировка потока | тормозит анимацию | занимает поток пула, отнимая его у чужих запросов |

Отсюда следует всё дальнейшее: любое изменяемое поле в singleton-бине - это разделяемое состояние, а любая блокирующая операция - занятый поток пула.

## Теория и где она в проекте

| Тема | Где смотреть |
|---|---|
| Spring Boot lifecycle, автоконфигурация | [Application.kt](src/main/kotlin/study/week1/Application.kt) - одна аннотация запускает component scan и auto-configuration |
| HTTP semantics: методы, коды, заголовки | [HttpController.kt](src/main/kotlin/study/week1/HttpController.kt) - `Cache-Control` на `/hello` показывает, что контракт живёт не только в JSON body |
| Идемпотентность | комментарий в `echo`: POST повторяем, но не идемпотентен по контракту |
| DTO и Kotlin data classes | `EchoRequest`/`EchoResponse`, десериализация через `jackson-module-kotlin` |
| Gradle lifecycle, toolchain, wrapper | [build.gradle.kts](build.gradle.kts) - JDK 17 toolchain, версии плагинов зафиксированы |
| Тест как HTTP-контракт | [HttpControllerTest.kt](src/test/kotlin/study/week1/HttpControllerTest.kt) - MockMvc проверяет код ответа и тело без реального сокета |

## Запуск

```bash
./gradlew test
./gradlew bootRun
```

```bash
curl -i localhost:8080/health
curl -i 'localhost:8080/hello?name=kotlin'
curl -i -H 'Content-Type: application/json' -H 'X-Request-Id: r-1' \
     -d '{"message":"hi"}' localhost:8080/echo
```

Обратите внимание на `Cache-Control: max-age=30` в ответе `/hello` и на возврат `X-Request-Id` в теле `/echo`: сквозной идентификатор запроса вернётся на неделе 12 уже как элемент диагностики.

Сборка без IDE - обязательный пункт критерия готовности:

```bash
./gradlew bootJar
java -jar build/libs/*.jar
```

## Задания

1. Нарисовать request lifecycle: сокет -> Tomcat connector -> пул рабочих потоков -> `DispatcherServlet` -> `HandlerMapping` -> контроллер -> `HttpMessageConverter` -> ответ. Проверить себя, поставив breakpoint в контроллере и прочитав stack trace целиком.
2. Добавить endpoint `GET /slow`, который спит 5 секунд, и ограничить пул: `server.tomcat.threads.max=4`. Отправить 20 параллельных запросов и увидеть, как `/health` перестаёт отвечать мгновенно:

   ```bash
   for i in $(seq 20); do curl -s -o /dev/null localhost:8080/slow & done
   time curl -s localhost:8080/health
   ```

   Это и есть thread-pool saturation, о которой говорит план. Запишите время ответа `/health` до и после нагрузки.
3. Завести профили `local` и `test` (`application-local.yaml`, `application-test.yaml`), развести порт и уровень логирования, запустить с `--spring.profiles.active=local`.
4. Добавить `HEAD /health` и сравнить вывод `curl -I` с `curl -i`.
5. Посмотреть `./gradlew dependencies --configuration runtimeClasspath` и найти, откуда приходит Tomcat, которого нет в ваших зависимостях.

## Что разобрать с ментором

- Какие потоки обслуживают запросы и что именно произойдёт при блокирующей операции в контроллере.
- Что Spring Boot создал автоматически и где заканчивается автоконфигурация и начинается ваш код.
- Почему singleton-бин с изменяемым полем - это баг, который не воспроизводится на одном пользователе.

## Критерий готовности

- Можешь нарисовать request lifecycle от TCP/HTTP до контроллера и обратно, не подглядывая.
- Можешь собрать и запустить приложение без IDE.
- Можешь объяснить thread-pool saturation по своему замеру, а не по описанию.

## Контрольные вопросы

- Почему HTTP называется stateless и что тогда делают cookie и token?
- Чем идемпотентность отличается от повторяемости запроса? Идемпотентен ли `POST /echo`?
- Что такое classpath и почему возможен конфликт версий зависимостей?
- Чем `./gradlew bootRun` отличается от `java -jar` с точки зрения classpath и жизненного цикла процесса?

## Материалы

- [Spring Boot Reference: Developing with Spring Boot](https://docs.spring.io/spring-boot/reference/using/index.html)
- [Spring Framework: Web MVC](https://docs.spring.io/spring-framework/reference/web/webmvc.html)
- [MDN: HTTP request methods](https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods)
- [Gradle: Build Lifecycle](https://docs.gradle.org/current/userguide/build_lifecycle.html)
