# Review

Статус: HTTP-тесты проходят; filter chain возвращает `401` без token и `403` для чужого account. Bcrypt, TTL access/refresh grants, одноразовая rotation и ownership check разделены с бизнес-кодом. Bearer token не сохраняется в `SecurityContext.credentials`.

Критичное ограничение: users/tokens/accounts хранятся in-memory, refresh token хранится открыто. Проект годится только для лаборатории; перед зачётом нужны DB-backed hashed refresh tokens, logout/revoke-all и audit events.
