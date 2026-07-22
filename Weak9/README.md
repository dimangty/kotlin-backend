# Неделя 9. Authentication, authorization и API security

Учебный сервис использует bcrypt и непрозрачные access/refresh tokens. Opaque tokens выбраны вместо самописного JWT: цель — lifecycle, rotation/revoke и ownership, а не криптография JWT.

## Задания

1. Перенести users/tokens/accounts в PostgreSQL и хранить только hash refresh token; учебные access/refresh grants сейчас имеют TTL, но остаются in-memory.
2. Добавить logout/revoke всех сессий пользователя.
3. Расширить имеющиеся negative tests (чужой account, отсутствие token и replay refresh) сценариями неверного пароля и истёкшего token.
4. Добавить audit event без password/token/PII.

В production используйте стандартную JWT/OAuth2 библиотеку и внешний secret storage; не копируйте учебное in-memory хранилище.
