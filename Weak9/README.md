# Неделя 9. Authentication, authorization и API security

Учебный сервис использует bcrypt и непрозрачные access/refresh tokens. Opaque tokens выбраны вместо самописного JWT: цель — lifecycle, rotation/revoke и ownership, а не криптография JWT.

## Задания

1. Перенести users/tokens/accounts в PostgreSQL и хранить только hash refresh token.
2. Добавить logout/revoke всех сессий пользователя.
3. Написать negative tests: чужой account, replay refresh, неверный пароль, отсутствие token.
4. Добавить audit event без password/token/PII.

В production используйте стандартную JWT/OAuth2 библиотеку и внешний secret storage; не копируйте учебное in-memory хранилище.

