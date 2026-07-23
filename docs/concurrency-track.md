# Углублённый трек: транзакции и конкурентность

Обязательное дополнение к неделям 6-7, 10 и 11. Транзакции изучаются через timeline нескольких сессий: **пока аномалия не воспроизведена руками, определение считается неусвоенным.**

## Каталог проблем

| Проблема | Как возникает | Типовые способы защиты | Где отрабатывается |
|---|---|---|---|
| Lost update | два клиента читают одно значение и записывают новые | atomic UPDATE, version check, row lock, Serializable | `Weak6/session-a.sql` секции 2-3 |
| Double processing | запрос повторился после timeout/retry | idempotency key + UNIQUE + сохранённый результат | `Weak7` (`TransferService`), `Weak11`, `Weak16` |
| Write skew | транзакции меняют разные строки, ломая общий инвариант | Serializable, явная блокировка, смена модели | `Weak6/session-a.sql` секция 5 |
| Deadlock | ресурсы захватываются в разном порядке | единый lock order, короткие транзакции, retry жертвы | `Weak7/locks-session-{a,b}.sql` секция 2 |
| Long transaction | транзакция держит snapshot и locks во время внешней работы | сузить границы, не ходить в сеть внутри транзакции | `Weak11/PaymentCoordinator.kt` |
| Hot row | много операций меняют одну строку баланса | ledger, агрегация, осознанная сериализация | `Weak7`, `Weak16` |

## Семь обязательных экспериментов

- [ ] **1. Snapshot и видимость строк в двух сессиях.** `Weak6/session-a.sql` секции 1 и 4: non-repeatable read в Read Committed и стабильный snapshot в Repeatable Read.
- [ ] **2. Lock wait и определение блокирующей сессии.** `Weak7/locks-session-{a,b}.sql` секция 1 плюс `Weak7/locks-inspect.sql`: `pg_blocking_pids()`, `pg_locks` с `granted = false`. Взгляд на ту же проблему со стороны инцидента - `Weak12/slow-query-lab.sql` блок 5: как отличить «медленно из-за плана» от «медленно из-за блокировки».
- [ ] **3. Deadlock и выбор жертвы.** `Weak7/locks-session-{a,b}.sql` секция 2. Зафиксировать `SQLSTATE 40P01` и то, какую транзакцию откатила база - это решение принимает PostgreSQL, а не приложение.
- [ ] **4. Lost update и минимум два способа починки.** `Weak6/session-a.sql` секции 2-3 (atomic UPDATE), затем `Weak6-1/AccountService.kt` и `SerializableDebitService.kt` - те же два подхода в приложении.
- [ ] **5. Serialization failure и ограниченный retry всей транзакции.** `Weak6/session-a.sql` секция 5, `Weak6-1/SerializableDebitService.kt`.
- [ ] **6. Параллельные переводы и проверка инварианта суммы.** `Weak7`, `Weak10` и `Weak16`: тесты на 50 конкурентных переводов на настоящем PostgreSQL.
- [ ] **7. Повтор HTTP-запроса после timeout.** `Weak11/PaymentCoordinatorTest.kt` и idempotency-тесты `Weak7`/`Weak16`.

## Что должно быть в отчёте

Для каждого эксперимента:

1. timeline двух или более транзакций по шагам, с указанием isolation level;
2. что видела каждая транзакция в каждый момент;
3. какой инвариант нарушился и как именно;
4. на каком уровне поставлена защита: код, граница транзакции, constraint или isolation level;
5. если добавлен retry - какие именно SQLSTATE считаются retryable и почему остальные повторять нельзя.

## Классификация ошибок для retry

Повторять целиком имеет смысл только классифицированные ошибки:

| SQLSTATE | Что это | Retry |
|---|---|---|
| `40001` | serialization failure | да, всю бизнес-транзакцию |
| `40P01` | deadlock detected | да, всю бизнес-транзакцию |
| `55P03` | lock timeout | осторожно, с backoff |
| `23505` | unique violation | нет, это конфликт данных, а не сбой |
| `23514` | check violation | нет, это ошибка бизнес-правила |
| `57014` | statement cancelled | нет, решение принял клиент или админ |

Повторять нужно **всю** бизнес-транзакцию, а не последний statement: после отката база откатила всё, включая уже сделанные шаги.

## Правило для финтеха

Операция не считается корректной только потому, что проходит одиночный happy-path тест. Нужны:

- ограничения на уровне базы (UNIQUE, CHECK, FOREIGN KEY);
- конкурирующие транзакции;
- повтор запроса с тем же idempotency key;
- timeout и поведение при неизвестном результате внешнего вызова;
- проверка инварианта суммы после конкурентной нагрузки.

## Типовые ошибки трека

- Проверять MVCC на H2. H2 не воспроизводит ни MVCC PostgreSQL, ни его locks, ни его planner.
- Считать, что Repeatable Read защищает от lost update при read-compute-write. Он даёт стабильный snapshot, а не атомарность вашей логики.
- Ловить unique violation внутри `@Transactional` без `ON CONFLICT`: транзакция уже в aborted state, дальнейшие statements не выполнятся.
- Держать открытую транзакцию во время HTTP-вызова во внешний сервис.
- Бесконечный retry без потолка попыток и без backoff.
- Тест на конкурентность без barrier/latch: потоки успевают отработать последовательно, и тест ничего не проверяет.

## Материалы

- [Concurrency Control](https://www.postgresql.org/docs/17/mvcc.html)
- [Transaction Isolation](https://www.postgresql.org/docs/17/transaction-iso.html)
- [Explicit Locking](https://www.postgresql.org/docs/17/explicit-locking.html)
- [PostgreSQL Error Codes](https://www.postgresql.org/docs/17/errcodes-appendix.html)
