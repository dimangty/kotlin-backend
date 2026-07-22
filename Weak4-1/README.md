# Неделя 4-1. B-tree: устройство индекса и процесс поиска

Spring Boot/Kotlin-приложение создаёт события, выполняет UUID lookup, показывает реальный `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` и размеры heap/индексов. Исходная миллионная SQL-лаборатория сохранена в `lab.sql` для отдельного ручного эксперимента.

## Запуск

```bash
docker compose up -d
./gradlew bootRun
```

PostgreSQL слушает `localhost:5434`. Маршруты:

- `POST /api/index-lab/events/generate` с JSON `{"count":10000}`
- `GET /api/index-lab/events/{publicId}`
- `GET /api/index-lab/events/{publicId}/plan`
- `GET /api/index-lab/distribution`
- `GET /api/index-lab/sizes`

## Лаборатория

1. Сравнить план UUID lookup до и после удаления/возврата индекса.
2. Увеличить данные до миллиона строк и проверить, почему `status='DONE'` обычно даёт Seq Scan.
3. Сравнить размер и скорость вставки при 0, 1 и 3 вторичных индексах.
4. Нарисовать путь root → internal → leaf → heap tuple и объяснить, почему `O(log n)` не описывает I/O стоимость полностью.

`lab.sql` создаёт собственную таблицу, поэтому запускайте его в отдельной пустой базе, а не поверх Flyway-схемы приложения.

Проверка: `./gradlew test` запускает PostgreSQL 17 через Testcontainers и подтверждает выбор UUID B-tree.
