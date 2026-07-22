# Неделя 13. Docker, CI/CD и safe deploy

Multi-stage image запускается non-root, filesystem read-only, приложение имеет readiness и graceful shutdown. CI тестирует код до сборки image.

```bash
docker compose up --build
curl localhost:8080/actuator/health/readiness
```

Задания: добавить PostgreSQL и migration check, спроектировать expand-contract в два релиза, описать backup/restore и rollback. Проверьте запуск на чистой машине.

