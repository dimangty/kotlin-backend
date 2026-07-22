# Неделя 14. Ktor как явный server stack

Те же HTTP concepts реализованы через routing/plugins вместо Spring annotations и auto-configuration.

```bash
./gradlew run
curl localhost:8080/health
curl -H 'Authorization: Bearer study-token' localhost:8080/payments/42
```

Сравните с `Weak1`: startup, wiring, error mapping, authentication и test API. Знания HTTP, DTO, auth и transaction boundaries остаются переносимыми.

