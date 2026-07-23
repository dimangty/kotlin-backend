# Неделя 16. Финальный финтех-срез и подготовка к рабочим задачам

**Результат недели:** показать законченный вертикальный сценарий и пройти mock review по нему как по production merge request.

Здесь ничего нового не изучается. Неделя проверяет, собираются ли выводы недель 3-15 в один работающий срез: счёт -> идемпотентный перевод -> упорядоченные блокировки -> двусторонний ledger -> audit -> история с cursor pagination.

## Карта решений

| Решение | Реализация | Откуда взялось |
|---|---|---|
| Деньги в minor units (`bigint`) | схема [V1__schema.sql](src/main/resources/db/migration/V1__schema.sql) | неделя 3 |
| Инварианты в базе, а не только в коде | `CHECK(balance_minor >= 0)`, `CHECK(amount_minor > 0)`, `transfers_accounts_differ` | недели 3 и 10 |
| Идемпотентность | `UNIQUE(idempotency_key)` + `ON CONFLICT DO NOTHING` + повторная проверка после блокировок | недели 7 и 11 |
| Профилактика deadlock | `ORDER BY id FOR UPDATE` по отсортированным UUID | неделя 7 |
| Ledger вместо одного mutable balance | `ledger_entries` с `UNIQUE(transfer_id, account_id)`, две проводки на перевод | неделя 7 |
| Cursor pagination истории | индекс `ledger_entries(account_id, id DESC) INCLUDE (transfer_id, amount_minor)` | недели 5 и 2 |
| Audit без секретов | `audit_events(actor, event_type, entity_id)`, тела запроса нет | недели 9 и 12 |
| Границы транзакции | `@Transactional` на одном методе сервиса, сети внутри нет | недели 8 и 11 |
| Ограниченные идентификаторы | `varchar(128)` для `idempotency_key` и `actor` ([V3](src/main/resources/db/migration/V3__bounded_operation_identifiers.sql)) | неделя 12 |

Архитектурные решения и timeline отказа - в [docs/architecture.md](docs/architecture.md).

## Запуск

```bash
docker compose up -d          # PG_PORT=55432 docker compose up -d, если 5432 занят
./gradlew test                # включая 50 встречных переводов и двойной retry
./gradlew bootRun
```

```bash
OWNER=$(uuidgen)
A=$(curl -s -X POST localhost:8080/accounts -H 'Content-Type: application/json' \
     -d "{\"ownerId\":\"$OWNER\",\"currency\":\"EUR\",\"initialBalanceMinor\":1000}" | jq -r .id)
B=$(curl -s -X POST localhost:8080/accounts -H 'Content-Type: application/json' \
     -d "{\"ownerId\":\"$(uuidgen)\",\"currency\":\"EUR\",\"initialBalanceMinor\":0}" | jq -r .id)

# Перевод и его повтор с тем же ключом: один и тот же id, балансы не меняются дважды
curl -s -X POST localhost:8080/transfers -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: demo-1' -H 'X-Actor: demo-user' \
     -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amountMinor\":250}"
curl -s -X POST localhost:8080/transfers -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: demo-1' -H 'X-Actor: demo-user' \
     -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amountMinor\":250}"

curl -s "localhost:8080/accounts/$A/ledger?limit=10"
```

## Задания

1. **Аутентификация и ownership.** Перенести неделю 9: перевод разрешён только владельцу счёта-источника, `X-Actor` заменяется на principal из токена. Сейчас actor приходит заголовком, то есть подделывается клиентом - для финального среза это дефект, а не упрощение.
2. **Bounded retry.** Ограниченный повтор всей транзакции для `40001` и `40P01` с backoff и потолком; `23505` и `23514` не повторяются. Тест на каждый случай.
3. **Rollback после инъецированного сбоя.** Тест: сбой между обновлением балансов и вставкой проводок откатывает всё; баланс, transfers и ledger остаются согласованными. Проверяется атомарность, а не happy path.
4. **EXPLAIN на реальном объёме.** Сгенерировать миллион записей ledger и снять `EXPLAIN (ANALYZE, BUFFERS)` для истории по счёту до и после индекса. Подтвердить Index Only Scan и `Heap Fetches: 0`. Отчёт - по форме из [docs/index-track.md](../docs/index-track.md).
5. **Operation ID сквозь слои.** Добавить `operationId` из недели 12 в MDC, audit и ответ, чтобы один идентификатор связывал HTTP-запрос, транзакцию, проводки и запись аудита.
6. **Компенсация вместо удаления.** Реализовать отмену перевода как новую компенсирующую операцию с обратными проводками. Ledger остаётся неизменяемым - это требование раздела 5 плана.
7. **Пакет документов к ревью.** ER-диаграмма, список индексов с обоснованием, `EXPLAIN` до и после, timeline транзакций с объяснением профилактики deadlock, threat model и предположения по нагрузке.

## Mock review и mock interview

Финальная встреча проводится как ревью production merge request, а не как учебная сдача. Готовьтесь защищать решения фактами: планами выполнения, constraints и тестами.

Вопросы, которые прозвучат:

- Покажите путь от HTTP-запроса до физического чтения страниц базы.
- Где начинается и заканчивается транзакция и почему именно там?
- Какой инвариант защищён кодом, какой - границей транзакции, какой - constraint базы?
- Что произойдёт при двух одновременных одинаковых `POST /transfers`? А при встречных переводах A->B и B->A?
- Что сломается при 10 000 переводов в секунду на один счёт (hot row) и что вы сделаете первым?
- Какой запрос здесь самый дорогой и как вы это узнали?
- Какие ошибки вы повторяете автоматически и почему остальные повторять нельзя?

## Критерий готовности

- Можешь защищать архитектурные решения фактами, SQL-планами и тестами.
- Можешь безопасно взять небольшую backend-задачу под ревью опытного разработчика.
- Список оставшихся пробелов составлен письменно и понятен вам самому.

## Границы этого проекта

Проект - сильный учебный baseline, но не production-ready. Отсутствуют аутентификация и ownership, ограниченный retry, тест отката после инъецированного сбоя и артефакты `EXPLAIN` на реальном объёме - это задания 1-4. Вне рамок трека намеренно остались Kafka, микросервисы, distributed transactions, Kubernetes, sharding и distributed locks.

## Материалы

- [PostgreSQL: Concurrency Control](https://www.postgresql.org/docs/17/mvcc.html)
- [Martin Fowler: Event Sourcing](https://martinfowler.com/eaaDev/EventSourcing.html) - для сравнения с ledger-подходом
- [docs/index-track.md](../docs/index-track.md) и [docs/concurrency-track.md](../docs/concurrency-track.md) - формы отчёта, по которым принимается финальная работа
