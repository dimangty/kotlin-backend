# Неделя 8 — краткая теория

**Тема:** Spring Data, JDBC/JPA, миграции и границы транзакций.
**Результат:** перенести понимание SQL и транзакций в Spring, не пряча базу за ORM.

---

## 1. JDBC, пул и транзакция

- Соединение из HikariCP привязывается к транзакции через `TransactionSynchronizationManager` (ThreadLocal).
- Пока транзакция открыта, соединение занято. Долгая транзакция = дефицит соединений.
- Пул должен быть **меньше**, чем кажется: порядка `2 × ядра`. Каждое соединение — процесс PostgreSQL.
- Без явной транзакции работает autocommit.

## 2. JPA: жизненный цикл entity

```
new (transient) → persist() → managed → commit/flush → в БД
                                ↓ detach()/закрытие EM
                             detached
                                ↓ merge()
                             managed
```

**Dirty checking:** entity в состоянии managed сравнивается с исходным снимком при flush; изменённые поля превращаются в `UPDATE` автоматически — **`save()` вызывать не нужно**. Обратная сторона: случайное изменение поля улетит в базу.

Flush происходит: при коммите, перед запросом, затрагивающим изменённые сущности, и по явному `flush()`.

## 3. N+1

```
1 запрос: SELECT * FROM accounts WHERE user_id = ?
N запросов: SELECT * FROM payments WHERE account_id = ?   ← для каждого счёта
```

Лечится:

- `JOIN FETCH` в JPQL / `@EntityGraph`;
- `@BatchSize` / `hibernate.default_batch_fetch_size` (N+1 → N/размер батча);
- **projection** (интерфейсная или DTO-проекция) — читать только нужные колонки;
- явный SQL через `JdbcTemplate`.

Диагностика: `spring.jpa.show-sql` (грубо), datasource-proxy/p6spy (лучше), `pg_stat_statements` (правда).

## 4. Ловушки JPA

- `LazyInitializationException` — обращение к lazy-полю вне транзакции. Не лечится `open-in-view` (его надо **выключить**), лечится проекцией или fetch-планом.
- `@ManyToOne(fetch = EAGER)` по умолчанию — источник неявных JOIN.
- `@OneToMany` + `JOIN FETCH` + пагинация = Hibernate тянет всё в память (`HHH90003004`).
- `@Version` даёт оптимистичную блокировку; `@Lock(PESSIMISTIC_WRITE)` — `SELECT ... FOR UPDATE`.
- `equals/hashCode` по всем полям data class ломают identity сущностей.

## 5. `@Transactional`

- Работает через прокси → **self-invocation не открывает транзакцию**; `private`/`final` методы не перехватываются.
- `propagation`: `REQUIRED` (умолчание), `REQUIRES_NEW` (**второе соединение из пула**), `NESTED` (`SAVEPOINT`).
- `readOnly = true` на всех читающих методах.
- Ловля исключения внутри метода отменяет откат.
- Retry — **снаружи** транзакции.

## 6. Когда что

| Инструмент | Когда |
|---|---|
| JPA | CRUD над агрегатом, dirty checking, простые связи |
| `JdbcTemplate` | отчёты, сложные JOIN, оконные функции, batch, `RETURNING` |
| Явный SQL в JPA (`nativeQuery`) | точечно, когда нужен именно этот план |

Правило: **ни один критичный запрос не должен быть неожиданностью** — SQL и план проверены.

## 7. Flyway

- Версионные миграции `V1__init.sql`, применяются по порядку, фиксируются в `flyway_schema_history` с checksum.
- **Применённую миграцию не редактируют** — только новая.
- Миграции должны быть **backward-compatible**: старая версия приложения обязана работать со схемой после миграции (expand-contract).
- `CREATE INDEX CONCURRENTLY` не работает в транзакции → отдельная миграция.
- Опасно: `ALTER TABLE` под `ACCESS EXCLUSIVE`, `NOT NULL` на большой таблице, переименования.

---

## Контрольные вопросы

1. **Почему self-invocation обходит `@Transactional`?** Аннотация реализуется прокси; вызов внутри класса идёт напрямую по `this`.
2. **Что такое dirty checking и чем оно опасно?** Hibernate сам генерирует `UPDATE` для изменённых managed-сущностей; случайная правка поля тоже попадёт в базу.
3. **Почему `open-in-view` стоит выключать?** Он держит EntityManager (и соединение) на всё время рендеринга ответа и маскирует N+1.
4. **Почему нельзя править применённую миграцию?** Flyway сверяет checksum; на других окружениях схема уже изменена, и повторного применения не произойдёт.
