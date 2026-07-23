# Неделя 9. Аутентификация, авторизация и безопасность API

**Результат недели:** добавить безопасный доступ к данным, не смешивая security с бизнес-логикой.

Сервис использует bcrypt и непрозрачные (opaque) access/refresh tokens. Opaque tokens выбраны вместо самописного JWT сознательно: цель недели - lifecycle, rotation, revoke и ownership, а не криптография. Самописный JWT добавил бы класс ошибок, который к теме недели отношения не имеет.

## Аутентификация - это не авторизация

Разделение, вокруг которого построена вся неделя:

| Вопрос | Термин | Где в коде | Отказ |
|---|---|---|---|
| Кто ты? | authentication | [AccessTokenFilter.kt](src/main/kotlin/study/week9/AccessTokenFilter.kt) | `401` |
| Что тебе можно вообще? | authorization (RBAC) | `authorizeHttpRequests` в [SecurityConfig.kt](src/main/kotlin/study/week9/SecurityConfig.kt) | `403` |
| Можно ли тебе **этот объект**? | object-level authorization | проверка `account.ownerId` в [Api.kt](src/main/kotlin/study/week9/Api.kt) | `403` |

Третья строка - причина существования недели. Broken Object Level Authorization стоит первым номером в OWASP API Security Top 10 именно потому, что первые две проверки при этом работают безупречно: токен валиден, роль есть, а ресурс чужой.

## Теория и где она в проекте

| Тема | Где смотреть |
|---|---|
| Password hashing | `BCryptPasswordEncoder(12)`: cost 12 подобран так, чтобы проверка стоила заметного времени - это защита от перебора |
| Filter chain и principal | `AccessTokenFilter` кладёт в `SecurityContext` только `userId`; bearer token не сохраняется как credentials |
| Stateless-сессии | `SessionCreationPolicy.STATELESS` - сервер не хранит HTTP-сессию |
| Entry point | `authenticationEntryPoint` возвращает `401` без тела и без подсказок |
| TTL и rotation | `AuthService.issue` - access 15 минут, refresh 30 дней; `rotate` удаляет старый refresh, поэтому replay даёт `401` |
| Единый ответ на неверные данные | `login` не различает «нет пользователя» и «неверный пароль» - иначе API становится оракулом существующих email |
| Negative tests | [ApiSecurityTest.kt](src/test/kotlin/study/week9/ApiSecurityTest.kt) - `401` без токена, `403` чужому, `200` владельцу, `401` на повторный refresh |

## Запуск

```bash
./gradlew test
./gradlew bootRun
```

```bash
curl -i -H 'Content-Type: application/json' \
     -d '{"email":"owner@example.test","password":"correct-horse-battery-staple"}' \
     localhost:8080/auth/register

curl -s -H 'Content-Type: application/json' \
     -d '{"email":"owner@example.test","password":"correct-horse-battery-staple"}' \
     localhost:8080/auth/login

curl -i -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' \
     -d '{"balanceMinor":1000}' localhost:8080/accounts

curl -i localhost:8080/accounts/<id>                                   # 401
curl -i -H 'Authorization: Bearer <чужой токен>' localhost:8080/accounts/<id>   # 403
curl -i -X POST -H 'Refresh-Token: <refreshToken>' localhost:8080/auth/refresh  # 200, второй раз 401
```

## Ограничение проекта

Users, tokens и accounts хранятся in-memory, refresh token лежит открытым текстом. Это лаборатория, а не образец: копировать такое хранилище нельзя. В production берут стандартную JWT/OAuth2-библиотеку и внешнее хранилище секретов. Задания 1-2 закрывают именно этот разрыв.

## Задания

1. **Перенести в PostgreSQL.** Таблицы `users`, `accounts`, `refresh_tokens`. Refresh token хранить только как hash (как и пароль): утечка дампа базы не должна давать доступ к сессиям. Access grants можно оставить in-memory с TTL, но объяснить последствия для нескольких instance.
2. **Logout и revoke-all.** `POST /auth/logout` отзывает текущую сессию, `POST /auth/logout-all` - все сессии пользователя. Проверить тестом, что после revoke refresh перестаёт работать сразу, а не через 30 дней.
3. **Расширить negative tests.** Добавить: неверный пароль, истёкший access token, отсутствующий заголовок `Refresh-Token`, чужой refresh token, `403` на изменение чужого ресурса (не только на чтение).
4. **Audit trail.** Записывать `login`, `logout`, `refresh` и создание счёта: actor, тип события, идентификатор объекта, время. Не писать пароли, токены и PII. Формат событий согласовать с audit-таблицей недели 16.
5. **Rate limiting.** Ограничить попытки login по email и по IP, вернуть `429`. Объяснить, чем это отличается от блокировки аккаунта и почему блокировка сама по себе является вектором атаки.
6. **Проверить bcrypt cost.** Замерить время `encode` при cost 10, 12 и 14 и связать результат с тем, сколько login-запросов в секунду выдержит сервис.

## Что разобрать с ментором

- Negative security cases: показать не то, что работает, а то, что запрещено.
- Что нельзя помещать в JWT payload и почему opaque token в этом смысле проще.
- Где заканчивается ответственность фреймворка и начинается ownership check вашего домена.

## Критерий готовности

- Интеграционные тесты подтверждают запрет доступа к чужим ресурсам.
- Refresh token можно отозвать, не дожидаясь истечения access token.
- В логах и в ответах нет паролей, токенов и лишних данных пользователя.

## Контрольные вопросы

- Чем `401` отличается от `403` и когда сервер обязан вернуть `404` вместо `403`?
- Зачем нужна rotation refresh token, если у него и так есть срок жизни?
- Почему `login` не должен различать «нет такого email» и «неверный пароль»?
- Почему bcrypt намеренно медленный и что означает параметр cost?
- Что произойдёт с in-memory токенами при двух instance за балансировщиком?

## Материалы

- [Spring Security Reference](https://docs.spring.io/spring-security/reference/index.html)
- [OWASP API Security Top 10](https://owasp.org/API-Security/editions/2023/en/0x11-t10/)
- [OWASP: Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
