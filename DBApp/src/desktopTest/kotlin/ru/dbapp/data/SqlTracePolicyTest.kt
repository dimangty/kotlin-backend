package ru.dbapp.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Политика логирования скрывает подготовку стенда, но сохраняет учебные операции. */
class SqlTracePolicyTest {
    /** Обычные и многострочные варианты CREATE SCHEMA/TABLE не должны попадать в UI. */
    @Test
    fun `schema and table creation is hidden`() {
        assertFalse(shouldTraceSql("CREATE SCHEMA IF NOT EXISTS dbapp_lab"))
        assertFalse(
            shouldTraceSql(
                """
                -- Служебная подготовка стенда.
                CREATE TABLE IF NOT EXISTS accounts(id bigint)
                """,
            ),
        )
        assertFalse(shouldTraceSql("CREATE UNLOGGED TABLE events(id bigint)"))
        assertFalse(shouldTraceSql("CREATE TEMP TABLE session_data(id bigint)"))
    }

    /** Выбор безопасной учебной схемы выполняется при каждом подключении, но в лог не выводится. */
    @Test
    fun `search path setup is hidden`() {
        assertFalse(shouldTraceSql("SET search_path TO dbapp_lab, public"))
        assertFalse(shouldTraceSql("SET SESSION search_path = dbapp_lab, public"))
        assertFalse(shouldTraceSql("SET LOCAL search_path TO dbapp_lab"))
    }

    /** Индексы и прикладные запросы остаются подробно видимыми. */
    @Test
    fun `index and data operations remain visible`() {
        assertTrue(shouldTraceSql("CREATE INDEX accounts_owner_idx ON accounts(owner)"))
        assertTrue(shouldTraceSql("INSERT INTO accounts(owner) VALUES ('Alice')"))
        assertTrue(shouldTraceSql("SELECT * FROM accounts"))
    }
}
