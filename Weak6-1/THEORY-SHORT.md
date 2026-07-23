# Неделя 6-1 — краткая теория (Spring service + транзакции)

Та же неделя, что и [Weak6](../Weak6/), но со стороны сервисного слоя: где начинается транзакция и как выглядит retry в Kotlin.

---

## 1. Что нужно помнить из SQL-части

- **Read Committed** берёт snapshot на каждый оператор, **Repeatable Read/Serializable** — один на транзакцию.
- Read Committed допускает non-repeatable read, phantom и **lost update** при read-modify-write в коде.
- Repeatable Read даёт `40001` вместо lost update, но не ловит **write skew**.
- Serializable (SSI) ловит и write skew, но **требует retry**.
- Dirty read в PostgreSQL невозможен.

## 2. Границы транзакции в Spring

- `@Transactional` работает через **прокси**: вызов метода изнутри того же класса (self-invocation) транзакцию **не открывает**.
- `private`, `final` и `internal`-методы прокси не перехватывает (для Kotlin нужен `spring-kotlin` plugin `all-open`, он уже включён стартером).
- Транзакция начинается на входе в публичный метод бина и коммитится на выходе.
- Правило: транзакция открывается **в сервисе**, а не в контроллере и не в репозитории.

## 3. Что важно знать про @Transactional

| Атрибут | Смысл |
|---|---|
| `isolation` | `Isolation.REPEATABLE_READ`, `SERIALIZABLE` |
| `propagation` | `REQUIRED` (по умолчанию), `REQUIRES_NEW`, `NOT_SUPPORTED` |
| `readOnly = true` | подсказка + `SET TRANSACTION READ ONLY` |
| `timeout` | ограничение длительности |
| `rollbackFor` | по умолчанию откат **только на unchecked**; в Kotlin все исключения unchecked, так что это работает ожидаемо |

`REQUIRES_NEW` берёт **второе соединение** из пула — при неаккуратном использовании это путь к исчерпанию пула и самоблокировке.

## 4. Retry

- Ловить `CannotSerializeTransactionException` (40001) и `DeadlockLoserDataAccessException` (40P01).
- Retry **снаружи** транзакции: аннотированный метод должен завершиться (откатиться) до повтора. Retry внутри того же `@Transactional` бессмысленен — транзакция уже aborted.
- 3-5 попыток, экспоненциальный backoff + jitter.
- Не повторять `DuplicateKeyException` и `DataIntegrityViolationException`.

```kotlin
// Порядок слоёв: retry → transaction
class TransferFacade(private val service: TransferService) {
    fun transfer(cmd: Transfer) = retryOnSerializationFailure { service.doTransfer(cmd) }
}
```

## 5. Безопасное списание в коде

```kotlin
// 1. Атомарный UPDATE — предпочтительно
val updated = jdbc.update(
    "UPDATE accounts SET balance_minor = balance_minor - :amt WHERE id = :id AND balance_minor >= :amt",
    params)
if (updated == 0) throw InsufficientFunds()

// 2. Оптимистично
"UPDATE accounts SET balance_minor = :new, version = version + 1 WHERE id = :id AND version = :ver"

// 3. Пессимистично
"SELECT balance_minor FROM accounts WHERE id = :id FOR UPDATE"

// 4. Serializable + retry
```

## 6. Что запрещено внутри транзакции

- HTTP-вызовы и любые сетевые ожидания.
- `Thread.sleep`, ожидание блокировок без `lock_timeout`.
- Долгие вычисления.
- Отправка событий, которые нельзя откатить (используйте outbox — недели 7 и 11).

---

## Контрольные вопросы

1. **Почему self-invocation не открывает транзакцию?** `@Transactional` реализован прокси; внутренний вызов идёт напрямую по `this`, минуя прокси.
2. **Где должен быть retry относительно `@Transactional`?** Снаружи: транзакция должна откатиться полностью, после чего бизнес-операция выполняется заново с чтением свежих данных.
3. **Чем опасен `REQUIRES_NEW`?** Он занимает второе соединение из пула на время вложенной транзакции; при насыщении пула возможна взаимная блокировка.
4. **Почему `readOnly = true` полезен?** Он документирует намерение, позволяет драйверу/базе оптимизации и защищает от случайной записи.
