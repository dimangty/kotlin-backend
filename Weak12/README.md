# Неделя 12. Logs, metrics и диагностика

Фильтр связывает request ID, response header, structured log context и timer. Actuator экспортирует health/metrics/Prometheus.

```bash
./gradlew bootRun
curl -H 'X-Request-Id: incident-42' 'localhost:8080/work?millis=50'
curl localhost:8080/actuator/prometheus
```

Задания: добавить operationId, Hikari/transaction metrics, slow-query logging и incident checklist «lock wait vs bad plan». Не логировать Authorization, tokens, пароли и PII.

