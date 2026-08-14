package ru.dbappweb.model

/**
 * Каталог повторяет структуру конспекта и содержит только те примеры, для которых есть исполняемый сценарий.
 * Короткие описания объясняют ожидаемый результат до запуска SQL.
 */
object DemoCatalog {
    /** Шесть обязательных тем стартового экрана в том же порядке, что и в задании. */
    val topics: List<DemoTopic> = listOf(
        DemoTopic(
            id = "acid",
            title = "ACID",
            subtitle = "Атомарность, ограничения, изоляция промежуточного состояния и устойчивый COMMIT.",
            examples = listOf(
                example("acid-transfer", "Денежный перевод", "Списание и зачисление выполняются одной транзакцией."),
                example("acid-error", "Ошибка внутри транзакции", "CHECK отклоняет отрицательный баланс, после чего нужен ROLLBACK."),
                example("acid-savepoint", "SAVEPOINT: частичный откат", "Необязательный подшаг откатывается без отмены всей транзакции."),
            ),
        ),
        DemoTopic(
            id = "anomalies",
            title = "Аномалии",
            subtitle = "Конкурентные чтения и записи: что именно меняется и почему прикладная проверка может проиграть гонку.",
            examples = listOf(
                example("anomaly-dirty-read", "Dirty read", "PostgreSQL не показывает чужую незакоммиченную версию даже на READ UNCOMMITTED."),
                example("anomaly-non-repeatable", "Non-repeatable read", "Два SELECT на READ COMMITTED получают разные значения одной строки."),
                example("anomaly-phantom", "Phantom read", "Повторный запрос видит новую строку, подходящую под прежний предикат."),
                example("anomaly-lost-update", "Lost update", "Плохая схема «прочитал - посчитал - записал» теряет одно изменение."),
                example("anomaly-write-skew", "Write skew", "Две разные строки меняются корректно, но общий инвариант нарушается."),
            ),
        ),
        DemoTopic(
            id = "isolation",
            title = "Уровни изоляции",
            subtitle = "Новые снимки операторов, стабильный snapshot, конфликты записи и обязательный retry.",
            examples = listOf(
                example("isolation-read-uncommitted", "Read Uncommitted в PostgreSQL", "Фактическая видимость соответствует Read Committed."),
                example("isolation-read-committed-snapshot", "Read Committed: новый снимок", "Каждый оператор видит свежий зафиксированный снимок."),
                example("isolation-atomic-debit", "Условное списание без SELECT", "Проверка и изменение объединены в UPDATE ... RETURNING."),
                example("isolation-update-reevaluation", "Re-evaluation условия UPDATE", "Ожидающий UPDATE повторно проверяет WHERE на новой версии строки."),
                example("isolation-repeatable-snapshot", "Repeatable Read: стабильный снимок", "Повторное чтение остаётся на snapshot начала транзакции."),
                example("isolation-repeatable-conflict", "Repeatable Read: конфликт UPDATE", "Попытка изменить устаревшую строку заканчивается SQLSTATE 40001."),
                example("isolation-repeatable-write-skew", "Repeatable Read: write skew", "Snapshot isolation не защищает инвариант по разным строкам."),
                example("isolation-serializable", "Serializable ломает опасный цикл", "SSI отменяет одну транзакцию, после чего вся операция повторяется."),
                example("isolation-report", "Уровень для длинного отчёта", "READ ONLY-транзакция читает несколько показателей из одного снимка."),
            ),
        ),
        DemoTopic(
            id = "locks",
            title = "Блокировки",
            subtitle = "Row locks, очереди воркеров, advisory locks, MVCC и диагностика ожиданий.",
            examples = listOf(
                example("lock-for-update", "Пессимистическая блокировка счёта", "FOR UPDATE удерживает строку до конца транзакции."),
                example("lock-nowait", "NOWAIT: не ждать блокировку", "Занятая строка немедленно возвращает SQLSTATE 55P03."),
                example("lock-skip-locked", "SKIP LOCKED: очередь воркеров", "Два воркера забирают разные задачи и не ждут друг друга."),
                example("lock-advisory", "Advisory lock: прикладной mutex", "Транзакционный advisory lock координирует бизнес-ключ."),
                example("lock-diagnostics", "Диагностика ожиданий", "pg_locks и pg_blocking_pids показывают ожидающую сессию."),
                example("lock-mvcc", "MVCC: версии строки", "ctid и xmin меняются, потому что UPDATE создаёт новый tuple."),
                example("lock-hot", "HOT-обновление и лишний индекс", "Индекс изменяемого столбца мешает Heap-Only Tuple update."),
                example("lock-optimistic", "Optimistic locking с version", "Устаревшая версия обновляет ноль строк вместо затирания данных."),
            ),
        ),
        DemoTopic(
            id = "deadlocks",
            title = "Дедлоки",
            subtitle = "Цикл ожидания, SQLSTATE 40P01, единый порядок ресурсов и ограничивающие ущерб таймауты.",
            examples = listOf(
                example("lock-deadlock", "Воспроизводимый дедлок", "Обратный порядок строк создаёт цикл и SQLSTATE 40P01."),
                example("lock-order", "Исправление: единый порядок", "ORDER BY id FOR UPDATE оставляет ожидание, но разрывает цикл."),
                example("lock-timeouts", "Таймауты как страховка", "lock_timeout ограничивает ожидание, но не исправляет порядок доступа."),
            ),
        ),
        DemoTopic(
            id = "indexes",
            title = "Индексы",
            subtitle = "EXPLAIN (ANALYZE, BUFFERS), селективность, способы сканирования и типы индексов PostgreSQL.",
            examples = listOf(
                example("index-seq-vs-index", "Seq Scan до индекса", "План поиска customer_id сравнивается до и после B-tree."),
                example("index-range-order", "Диапазон и ORDER BY + LIMIT", "B-tree обслуживает диапазон дат и обратную сортировку."),
                example("index-like", "LIKE: префикс и ведущий %", "Префикс образует диапазон, а суффикс размазан по дереву."),
                example("index-scan", "Index Scan", "Точечный поиск по первичному ключу получает строку через TID."),
                example("index-only", "Index Only Scan и INCLUDE", "Покрывающий индекс содержит все нужные запросу столбцы."),
                example("index-bitmap", "Bitmap Index Scan", "Два индекса объединяются перед чтением heap-страниц."),
                example("index-low-selectivity", "Seq Scan при низкой селективности", "Выбор почти всей таблицы делает индекс дороже."),
                example("index-composite", "Составной индекс", "Равенство по customer_id идёт раньше диапазона created_at."),
                example("index-partial", "Частичный индекс", "В индекс попадают только queued/new строки нужного hot-path."),
                example("index-expression", "Индекс по выражению", "lower(email) получает отдельный порядок и уникальность."),
                example("index-hash", "Hash: только равенство", "Hash-план сравнивается с универсальным B-tree."),
                example("index-gin-jsonb", "GIN для JSONB", "Инвертированный индекс ускоряет оператор containment @>."),
                example("index-gin-array", "GIN для массива", "Индекс на tags отвечает, в каких статьях есть postgresql."),
                example("index-gist-range", "GiST для пересечения диапазонов", "Оператор && ищет пересекающиеся бронирования."),
                example("index-brin", "BRIN для append-only лога", "Крошечные сводки по блокам отсекают старые диапазоны."),
                example("index-maintenance", "Обслуживание индексов", "ANALYZE, размеры и статистика использования помогают принять решение."),
            ),
        ),
    )

    /** Быстрый поиск тематического экрана по стабильному идентификатору. */
    fun topic(id: String): DemoTopic? = topics.firstOrNull { it.id == id }

    /** Полный набор идентификаторов используется тестом согласованности UI и JDBC-реестра. */
    val exampleIds: Set<String> = topics.flatMap { it.examples }.mapTo(linkedSetOf()) { it.id }

    /** Фабрика убирает визуальный шум из большого декларативного каталога. */
    private fun example(id: String, title: String, description: String): DemoExample =
        DemoExample(id = id, title = title, description = description)
}
