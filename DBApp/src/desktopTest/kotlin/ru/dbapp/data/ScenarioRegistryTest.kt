package ru.dbapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.dbapp.model.DemoCatalog

/** JVM-тест гарантирует, что каждая видимая кнопка имеет реализацию и нет недоступного сценария. */
class ScenarioRegistryTest {
    /** Сравниваются множества в обе стороны, поэтому тест ловит и пропуск, и забытый старый обработчик. */
    @Test
    fun `catalog and JDBC registry contain the same examples`() {
        assertEquals(DemoCatalog.exampleIds, PostgresDemoRunner.supportedExampleIds)
    }
}
