package ru.dbapp.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Общий тест защищает обязательную структуру стартового экрана от случайного изменения. */
class DemoCatalogTest {
    /** Названия и порядок шести кнопок прямо следуют пользовательскому заданию. */
    @Test
    fun `catalog contains six required topics in order`() {
        assertEquals(
            listOf("ACID", "Аномалии", "Уровни изоляции", "Блокировки", "Дедлоки", "Индексы"),
            DemoCatalog.topics.map { it.title },
        )
    }

    /** Дублированный id направил бы кнопку не в тот JDBC-сценарий. */
    @Test
    fun `example identifiers are unique and every topic has examples`() {
        val allExamples = DemoCatalog.topics.flatMap { it.examples }

        assertEquals(allExamples.size, allExamples.map { it.id }.toSet().size)
        assertTrue(DemoCatalog.topics.all { it.examples.size >= 3 })
    }
}
