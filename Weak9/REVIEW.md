# Review

Статус: проект компилируется; filter chain, bcrypt, access/refresh rotation и ownership check разделены с бизнес-кодом.

Критичное ограничение: users/tokens/accounts хранятся in-memory, token expiration и persistence отсутствуют. Проект годится только для лаборатории; перед зачётом нужны negative security tests и DB-backed hashed refresh tokens.

