package ru.dbappweb.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** Контрактный тест не даёт случайно потерять тему или связать две кнопки с одним сценарием. */
class DemoCatalogTest {
    @Test
    fun `каталог содержит шесть тем и 44 уникальных примера`() {
        val examples = DemoCatalog.topics.flatMap { it.examples }

        assertEquals(
            listOf("ACID", "Аномалии", "Уровни изоляции", "Блокировки", "Дедлоки", "Индексы"),
            DemoCatalog.topics.map { it.title },
        )
        assertEquals(44, examples.size)
        assertEquals(44, examples.map { it.id }.toSet().size)
    }
}
