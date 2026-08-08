package ru.dbapp.data

/**
 * Индексные примеры работают на 200 000 заказов, как рекомендует PDF.
 * Перед каждым сценарием удаляются только индексы с именами DBApp в отдельной схеме.
 */
internal object IndexScenarios {
    /** Каждая кнопка строит реальный EXPLAIN (ANALYZE, BUFFERS), а не заранее записанный план. */
    val scenarios: Map<String, DemoScenario> = mapOf(
        "index-seq-vs-index" to { seqScanVsIndex() },
        "index-range-order" to { rangeAndOrder() },
        "index-like" to { likePrefixAndSuffix() },
        "index-scan" to { indexScan() },
        "index-only" to { indexOnlyScan() },
        "index-bitmap" to { bitmapScan() },
        "index-low-selectivity" to { lowSelectivity() },
        "index-composite" to { compositeIndex() },
        "index-partial" to { partialIndex() },
        "index-expression" to { expressionIndex() },
        "index-hash" to { hashIndex() },
        "index-gin-jsonb" to { ginJsonb() },
        "index-gin-array" to { ginArray() },
        "index-gist-range" to { gistRange() },
        "index-brin" to { brinLog() },
        "index-maintenance" to { indexMaintenance() },
    )

    /** Базовый опыт сравнивает полное чтение heap и путь через B-tree/TID. */
    private fun ScenarioContext.seqScanVsIndex() = withOrders {
        cleanupDemoIndexes()
        db.open("index-seq-vs-index").use { connection ->
            val query = "SELECT * FROM orders WHERE customer_id = 4242"
            connection.execute("ANALYZE orders")
            log.step("План без индекса:")
            connection.explain(query)
            connection.execute("CREATE INDEX orders_customer_demo_idx ON orders(customer_id)")
            connection.execute("ANALYZE orders")
            log.step("План после B-tree:")
            connection.explain(query)
            log.result("Планировщик сам выбрал Index Scan или Bitmap Heap Scan по фактической селективности.")
        }
    }

    /** Один B-tree обслуживает непрерывный диапазон и чтение листьев в обратном порядке. */
    private fun ScenarioContext.rangeAndOrder() = withOrders {
        cleanupDemoIndexes()
        db.open("index-range-order").use { connection ->
            connection.execute("CREATE INDEX orders_created_demo_idx ON orders(created_at)")
            connection.execute("ANALYZE orders")
            val rangeQuery =
                """
                SELECT id, created_at, total
                FROM orders
                WHERE created_at >= now() - interval '1 day'
                """
            val orderQuery =
                """
                SELECT id, created_at
                FROM orders
                ORDER BY created_at DESC
                LIMIT 20
                """
            log.step("Диапазон последнего дня:")
            connection.explain(rangeQuery)
            log.step("ORDER BY created_at DESC LIMIT 20:")
            connection.explain(orderQuery)
            log.result("Связанные листья B-tree позволяют остановиться после первых 20 строк без полного Sort.")
        }
    }

    /** text_pattern_ops даёт диапазон для левого префикса, но не создаёт границу для ведущего %. */
    private fun ScenarioContext.likePrefixAndSuffix() = withOrders {
        cleanupDemoIndexes()
        db.open("index-like").use { connection ->
            connection.execute("CREATE INDEX orders_email_pattern_demo_idx ON orders(email text_pattern_ops)")
            connection.execute("ANALYZE orders")
            val prefix = "SELECT id FROM orders WHERE email LIKE 'user123%'"
            val suffix = "SELECT id FROM orders WHERE email LIKE '%@example.com'"
            log.step("LIKE 'user123%' — известна левая граница:")
            connection.explain(prefix)
            log.step("LIKE '%@example.com' — ведущий %:")
            connection.explain(suffix)
            log.result("Для подстроки обычно нужен pg_trgm + GIN/GiST, а не B-tree.")
        }
    }

    /** Первичный ключ уже имеет уникальный B-tree и подходит точечному поиску. */
    private fun ScenarioContext.indexScan() = withOrders {
        cleanupDemoIndexes()
        db.open("index-scan").use { connection ->
            val query = "SELECT * FROM orders WHERE id = 1000"
            log.step("План точечного поиска:")
            connection.explain(query)
            log.result("Index Scan на orders_pkey получает TID, затем читает полную строку из heap.")
        }
    }

    /** INCLUDE хранит payload в листьях, а VACUUM выставляет visibility map для Index Only Scan. */
    private fun ScenarioContext.indexOnlyScan() = withOrders {
        cleanupDemoIndexes()
        db.open("index-only").use { connection ->
            connection.execute(
                """
                CREATE INDEX orders_customer_cover_demo_idx
                ON orders(customer_id, created_at DESC)
                INCLUDE (total, status)
                """,
            )
            connection.execute("VACUUM (ANALYZE) orders")
            val query =
                """
                SELECT created_at, total, status
                FROM orders
                WHERE customer_id = 4242
                ORDER BY created_at DESC
                LIMIT 20
                """
            log.step("Покрывающий план:")
            connection.explain(query)
            log.result("Ищите Index Only Scan и Heap Fetches; all-visible страницы уменьшают обращения к heap.")
        }
    }

    /** Планировщик может собрать bitmap по одному или двум индексам и читать heap страницами. */
    private fun ScenarioContext.bitmapScan() = withOrders {
        cleanupDemoIndexes()
        db.open("index-bitmap").use { connection ->
            connection.execute("CREATE INDEX orders_status_demo_idx ON orders(status)")
            connection.execute("CREATE INDEX orders_total_demo_idx ON orders(total)")
            connection.execute("ANALYZE orders")
            val query =
                """
                SELECT *
                FROM orders
                WHERE status = 'paid'
                  AND total BETWEEN 1000 AND 1200
                """
            log.step("Cost-based план для средней выборки:")
            connection.explain(query)
            log.step("Bitmap Heap Scan группирует TID по heap-страницам; BitmapAnd появляется, только если оба bitmap выгодны.")
        }
    }

    /** Условие истинно почти для 100% строк, поэтому последовательное чтение обычно дешевле. */
    private fun ScenarioContext.lowSelectivity() = withOrders {
        cleanupDemoIndexes()
        db.open("index-low-selectivity").use { connection ->
            connection.execute("CREATE INDEX orders_status_demo_idx ON orders(status)")
            connection.execute("ANALYZE orders")
            val query = "SELECT * FROM orders WHERE status IN ('new', 'paid', 'cancelled')"
            log.step("Индекс существует, но выборка почти полная:")
            connection.explain(query)
            log.result("Seq Scan здесь является правильным решением планировщика, а не неисправностью индекса.")
        }
    }

    /** Равенство фиксирует левую группу, затем диапазон и сортировка работают по created_at. */
    private fun ScenarioContext.compositeIndex() = withOrders {
        cleanupDemoIndexes()
        db.open("index-composite").use { connection ->
            connection.execute(
                "CREATE INDEX orders_customer_created_demo_idx ON orders(customer_id, created_at DESC)",
            )
            connection.execute("ANALYZE orders")
            val goodQuery =
                """
                SELECT * FROM orders
                WHERE customer_id = 4242
                  AND created_at >= now() - interval '30 days'
                ORDER BY created_at DESC
                """
            val secondColumnOnly = "SELECT * FROM orders WHERE created_at >= now() - interval '1 hour'"
            log.step("Условия следуют порядку (equality, range):")
            connection.explain(goodQuery)
            log.step("Запрос только по второму столбцу:")
            connection.explain(secondColumnOnly)
            log.step("PostgreSQL 18 умеет B-tree skip scan, но его выбор зависит от статистики и не отменяет проектное правило левого префикса.")
        }
    }

    /** Частичный индекс физически содержит только new-строки и подходит предикату с тем же status. */
    private fun ScenarioContext.partialIndex() = withOrders {
        cleanupDemoIndexes()
        db.open("index-partial").use { connection ->
            connection.execute(
                "CREATE INDEX orders_new_created_demo_idx ON orders(created_at DESC) WHERE status = 'new'",
            )
            connection.execute("ANALYZE orders")
            val matching =
                """
                SELECT * FROM orders
                WHERE status = 'new'
                ORDER BY created_at DESC
                LIMIT 50
                """
            val missingPredicate = "SELECT * FROM orders ORDER BY created_at DESC LIMIT 50"
            log.step("Предикат запроса включает status='new':")
            connection.explain(matching)
            log.step("Без status индекс неполон и небезопасен:")
            connection.explain(missingPredicate)
            log.result("Partial index меньше полного и дешевле обслуживается, если hot-path всегда содержит его предикат.")
        }
    }

    /** Обычный порядок email не равен порядку lower(email), поэтому выражение индексируется отдельно. */
    private fun ScenarioContext.expressionIndex() = withOrders {
        cleanupDemoIndexes()
        db.open("index-expression").use { connection ->
            connection.execute("CREATE UNIQUE INDEX orders_email_lower_demo_uq ON orders(lower(email))")
            connection.execute("ANALYZE orders")
            val query = "SELECT * FROM orders WHERE lower(email) = lower('USER42@EXAMPLE.COM')"
            log.step("Функциональный индекс совпадает с выражением WHERE:")
            connection.explain(query)
            log.result("UNIQUE lower(email) одновременно обеспечивает регистронезависимый инвариант.")
        }
    }

    /** Hash тестируется отдельно от B-tree, чтобы один индекс не перетянул план на себя. */
    private fun ScenarioContext.hashIndex() = withOrders {
        cleanupDemoIndexes()
        db.open("index-hash").use { connection ->
            val query = "SELECT * FROM orders WHERE email = 'user42@example.com'"
            connection.execute("CREATE INDEX orders_email_hash_demo_idx ON orders USING hash(email)")
            connection.execute("ANALYZE orders")
            log.step("Hash — только равенство:")
            connection.explain(query)
            connection.execute("DROP INDEX orders_email_hash_demo_idx")
            connection.execute("CREATE INDEX orders_email_btree_demo_idx ON orders(email)")
            connection.execute("ANALYZE orders")
            log.step("B-tree — то же равенство плюс диапазоны/сортировка:")
            connection.explain(query)
            log.step("Hash выбирают только после измерений: ниша уже, чем у B-tree.")
        }
    }

    /** jsonb_path_ops компактен для containment @> и хранит обратное соответствие элемент → TID. */
    private fun ScenarioContext.ginJsonb() = withOrders {
        cleanupDemoIndexes()
        db.open("index-gin-jsonb").use { connection ->
            connection.execute(
                "CREATE INDEX orders_payload_gin_demo_idx ON orders USING gin(payload jsonb_path_ops)",
            )
            connection.execute("ANALYZE orders")
            val query =
                """
                SELECT * FROM orders
                WHERE payload @> '{"country":"RU","channel":"mobile"}'::jsonb
                """
            log.step("GIN jsonb_path_ops для @>:")
            connection.explain(query)
            log.result("GIN отвечает на вопрос «в каких документах есть этот набор ключей/значений?». ")
        }
    }

    /** Массивы демонстрируют ту же инвертированную идею на более простой структуре. */
    private fun ScenarioContext.ginArray() {
        cleanupDemoIndexes()
        prepareArticles()
        db.open("index-gin-array").use { connection ->
            connection.execute("CREATE INDEX articles_tags_gin_demo_idx ON articles USING gin(tags)")
            connection.execute("ANALYZE articles")
            val query = "SELECT * FROM articles WHERE tags @> ARRAY['postgresql']"
            log.step("GIN массива tags:")
            connection.explain(query)
            log.result("В индекс попадает каждый элемент массива и список строк, где он встречается.")
        }
    }

    /** GiST является инфраструктурой operator class; встроенный range-класс поддерживает пересечение &&. */
    private fun ScenarioContext.gistRange() {
        cleanupDemoIndexes()
        prepareReservations()
        db.open("index-gist-range").use { connection ->
            connection.execute("CREATE INDEX reservations_period_gist_demo_idx ON reservations USING gist(period)")
            connection.execute("ANALYZE reservations")
            val query =
                """
                SELECT * FROM reservations
                WHERE period && tstzrange(
                    '2026-08-10 10:00+00',
                    '2026-08-10 12:00+00',
                    '[)'
                )
                """
            log.step("GiST и оператор пересечения &&:")
            connection.explain(query)
            log.result("GiST подходит диапазонам и геометрии, где обычного линейного порядка B-tree недостаточно.")
        }
    }

    /** Коррелированный append-only порядок позволяет BRIN исключать целые диапазоны heap-страниц. */
    private fun ScenarioContext.brinLog() {
        cleanupDemoIndexes()
        prepareEvents()
        db.open("index-brin").use { connection ->
            val query =
                """
                SELECT * FROM events
                WHERE created_at >= '2026-05-18 00:00+00'
                  AND created_at <  '2026-05-19 00:00+00'
                """
            connection.execute(
                "CREATE INDEX events_created_brin_demo_idx ON events USING brin(created_at) WITH (pages_per_range = 64)",
            )
            connection.execute("ANALYZE events")
            log.step("BRIN на физически коррелированном времени:")
            connection.explain(query)
            val brinSize = connection.queryString("SELECT pg_size_pretty(pg_relation_size('events_created_brin_demo_idx'))")
            connection.execute("CREATE INDEX events_created_btree_demo_idx ON events(created_at)")
            val btreeSize = connection.queryString("SELECT pg_size_pretty(pg_relation_size('events_created_btree_demo_idx'))")
            log.result("Размер BRIN=$brinSize, B-tree=$btreeSize на одной таблице.")
            log.step("BRIN бесполезен без корреляции значения с физическим порядком строк.")
        }
    }

    /** Обслуживание начинается с измерений, а обычный VACUUM не обещает вернуть файл операционной системе. */
    private fun ScenarioContext.indexMaintenance() = withOrders {
        cleanupDemoIndexes()
        db.open("index-maintenance").use { connection ->
            connection.execute("CREATE INDEX orders_customer_demo_idx ON orders(customer_id)")
            connection.execute("ANALYZE orders")
            connection.explain("SELECT * FROM orders WHERE customer_id = 4242")
            connection.execute("VACUUM (ANALYZE) orders")
            val stats = connection.queryRows(
                """
                SELECT indexrelname,
                       pg_size_pretty(pg_relation_size(indexrelid)) AS size,
                       idx_scan,
                       idx_tup_read,
                       idx_tup_fetch
                FROM pg_stat_user_indexes
                WHERE schemaname = 'dbapp_lab' AND relname = 'orders'
                ORDER BY pg_relation_size(indexrelid) DESC
                """,
                maxRows = 30,
            )
            log.result("Статистика pg_stat_user_indexes:\n${stats.joinToString("\n") { "    $it" }}")
            val tableStats = connection.queryRows(
                """
                SELECT n_live_tup, n_dead_tup, last_analyze, last_autovacuum
                FROM pg_stat_user_tables
                WHERE schemaname = 'dbapp_lab' AND relname = 'orders'
                """,
            )
            log.result("Табличная статистика: ${tableStats.joinToString()}.")
            log.step("CREATE INDEX CONCURRENTLY и REINDEX CONCURRENTLY нужны на production, а не в этом изолированном стенде.")
        }
    }

    /** Общая подготовка не дублируется в шестнадцати индексных сценариях. */
    private inline fun ScenarioContext.withOrders(block: ScenarioContext.() -> Unit) {
        db.ensureOrders(log)
        block()
    }

    /** Удаляются только стабильные demo-имена внутри search_path dbapp_lab. */
    private fun ScenarioContext.cleanupDemoIndexes() {
        db.open("index-cleanup").use { connection ->
            connection.execute(
                """
                DROP INDEX IF EXISTS orders_customer_demo_idx;
                DROP INDEX IF EXISTS orders_created_demo_idx;
                DROP INDEX IF EXISTS orders_email_pattern_demo_idx;
                DROP INDEX IF EXISTS orders_customer_cover_demo_idx;
                DROP INDEX IF EXISTS orders_status_demo_idx;
                DROP INDEX IF EXISTS orders_total_demo_idx;
                DROP INDEX IF EXISTS orders_customer_created_demo_idx;
                DROP INDEX IF EXISTS orders_new_created_demo_idx;
                DROP INDEX IF EXISTS orders_email_lower_demo_uq;
                DROP INDEX IF EXISTS orders_email_hash_demo_idx;
                DROP INDEX IF EXISTS orders_email_btree_demo_idx;
                DROP INDEX IF EXISTS orders_payload_gin_demo_idx;
                DROP INDEX IF EXISTS articles_tags_gin_demo_idx;
                DROP INDEX IF EXISTS reservations_period_gist_demo_idx;
                DROP INDEX IF EXISTS events_created_brin_demo_idx;
                DROP INDEX IF EXISTS events_created_btree_demo_idx;
                """,
            )
        }
    }

    /** Небольшая детерминированная доля строк содержит тег postgresql. */
    private fun ScenarioContext.prepareArticles() {
        db.open("prepare-articles").use { connection ->
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS articles (
                    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    tags text[] NOT NULL
                )
                """,
            )
            val count = connection.queryLong("SELECT count(*) FROM articles")
            if (count < 20_000) {
                connection.execute("TRUNCATE TABLE articles RESTART IDENTITY")
                connection.executeUpdate(
                    """
                    INSERT INTO articles(tags)
                    SELECT CASE
                        WHEN g % 10 = 0 THEN ARRAY['postgresql', 'kotlin']
                        ELSE ARRAY['kotlin', 'backend']
                    END
                    FROM generate_series(1, 20000) AS g
                    """,
                )
            }
        }
    }

    /** Диапазоны распределены по 2026 году и дают селективное двухчасовое пересечение. */
    private fun ScenarioContext.prepareReservations() {
        db.open("prepare-reservations").use { connection ->
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS reservations (
                    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    room_id bigint NOT NULL,
                    period tstzrange NOT NULL
                )
                """,
            )
            val count = connection.queryLong("SELECT count(*) FROM reservations")
            if (count < 30_000) {
                connection.execute("TRUNCATE TABLE reservations RESTART IDENTITY")
                connection.executeUpdate(
                    """
                    INSERT INTO reservations(room_id, period)
                    SELECT
                        1 + (g % 200),
                        tstzrange(
                            '2026-01-01 00:00+00'::timestamptz + g * interval '20 minutes',
                            '2026-01-01 00:00+00'::timestamptz + g * interval '20 minutes' + interval '45 minutes',
                            '[)'
                        )
                    FROM generate_series(1, 30000) AS g
                    """,
                )
            }
        }
    }

    /** Время строго растёт вместе с физической вставкой — идеальная учебная корреляция для BRIN. */
    private fun ScenarioContext.prepareEvents() {
        db.open("prepare-events").use { connection ->
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS events (
                    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    created_at timestamptz NOT NULL,
                    payload jsonb NOT NULL
                )
                """,
            )
            val count = connection.queryLong("SELECT count(*) FROM events")
            if (count < 200_000) {
                log.step("Создаём 200 000 коррелированных событий для BRIN.")
                connection.execute("TRUNCATE TABLE events RESTART IDENTITY")
                connection.executeUpdate(
                    """
                    INSERT INTO events(created_at, payload)
                    SELECT
                        '2026-01-01 00:00+00'::timestamptz + g * interval '1 minute',
                        jsonb_build_object('event', g)
                    FROM generate_series(1, 200000) AS g
                    """,
                )
                connection.execute("ANALYZE events")
            }
        }
    }
}
