# Review

Миграция 24 июля 2026: Ktor 3.5.1 и Kotlin 2.3.21; три test-host теста проходят.

Статус: три теста и реальный EngineMain запуск успешны. Проверены `/health`, invalid `/echo`, отсутствие/ошибка bearer token и успешный защищённый запрос; YAML/serialization/status/auth plugins работают runtime.

Bearer token намеренно учебный. Следующий шаг: JWT verifier, negative auth tests и сравнение execution flow со Spring.
