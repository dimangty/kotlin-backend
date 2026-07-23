# Неделя 15 — краткая теория

**Тема:** Ktor + PostgreSQL — интеграция и тесты.
**Результат:** закрепить перенос базы, транзакций и тестов между Kotlin-фреймворками.

---

## 1. Выбор слоя доступа к данным

| Вариант | Плюсы | Минусы |
|---|---|---|
| Прямой JDBC (`DataSource` + `PreparedStatement`) | полный контроль над SQL и планом | много ручного маппинга |
| Exposed DSL | типобезопасные запросы, читаемо | свой диалект, часть SQL недоступна |
| Exposed DAO | ближе к ORM | те же риски, что у JPA (скрытые запросы) |

Для финтех-задач этого курса — **прямой SQL или Exposed DSL**. Правило недели 8 не меняется: критичный запрос не должен быть неожиданностью.

## 2. Пул и транзакции

```kotlin
class TransactionRunner(private val ds: DataSource, private val dispatcher: CoroutineDispatcher) {
    suspend fun <T> inTransaction(
        isolation: Int = Connection.TRANSACTION_READ_COMMITTED,
        block: (Connection) -> T,
    ): T = withContext(dispatcher) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            conn.transactionIsolation = isolation
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            }
        }
    }
}
```

- Нет прокси → нет self-invocation, нет `propagation`. Границы транзакции видны буквально.
- **JDBC блокирующий** → весь блок уходит на отдельный dispatcher, размер которого соотнесён с `maximumPoolSize`.
- Транзакция целиком внутри одного `withContext`: смена потока внутри транзакции недопустима.
- Сетевые вызовы внутри блока запрещены (неделя 11).

## 3. Flyway в Ktor

Миграции запускаются явно при старте модуля:

```kotlin
Flyway.configure().dataSource(ds).load().migrate()
```

Те же правила, что на неделях 8 и 13: только backward-compatible, `CREATE INDEX CONCURRENTLY` вне транзакции, `lock_timeout` в миграциях.

## 4. Error mapping

`SQLException.sqlState` → доменное исключение → `StatusPages` → HTTP:

| SQLSTATE | Ответ |
|---|---|
| 23505 unique_violation | `409` (или существующий результат для идемпотентности) |
| 23503 / 23514 | `409` / `422` |
| 40001 serialization_failure | retry |
| 40P01 deadlock_detected | retry |
| 55P03 lock_not_available | `409` / retry |

## 5. JWT principal и ownership

Ownership встраивается в запрос, а не проверяется после загрузки:

```sql
SELECT ... FROM accounts WHERE id = ? AND user_id = ?
```

Все правила недели 9 остаются в силе.

## 6. Тесты

- Testcontainers работает одинаково: поднять PostgreSQL, передать URL в конфигурацию `testApplication`.
- Конкурентный тест перевода: N корутин/потоков, барьер на старте, проверка инварианта суммы.
- Тест дедлока и retry после `40001` — повторить сценарии недель 7 и 10.
- Тест идемпотентности: параллельные `POST` с одним ключом → одна операция.

**Одинаковые инварианты должны проходить в обеих реализациях** — это и есть критерий недели.

---

## Контрольные вопросы

1. **Почему в Ktor границы транзакции виднее?** Транзакция — явный блок кода, а не аннотация, перехватываемая прокси; невозможно «случайно» не открыть её.
2. **Почему блок транзакции должен быть в одном `withContext`?** Соединение и его транзакция привязаны к конкретному вызову; смена потока внутри блока ломает корректность и усложняет отмену.
3. **Что изменилось в гарантиях данных по сравнению со Spring?** Ничего: MVCC, изоляция, блокировки и constraints — свойства PostgreSQL.
4. **Как выбрать между Exposed и прямым SQL?** По тому, нужен ли вам контроль над точным текстом запроса и планом; для финансовых запросов — нужен.
