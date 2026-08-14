package ru.dbapp.data

import ru.dbapp.model.DemoCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

/** Тест ломает сборку, если кнопка UI добавлена без JDBC-обработчика или наоборот. */
class ScenarioRegistryTest {
    @Test
    fun `каждый пример каталога имеет ровно один сценарий`() {
        assertEquals(DemoCatalog.exampleIds, ScenarioRegistry.supportedExampleIds)
        assertEquals(44, ScenarioRegistry.supportedExampleIds.size)
    }
}
