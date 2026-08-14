package ru.dbapp.data

import ru.dbapp.model.DemoReport

/** Контракт отделяет HTTP-координатор от JDBC-реализации и делает параллельную оркестрацию тестируемой. */
fun interface DemoScenarioExecutor {
    /** Ровно один участник согласованного HTTP-запуска выполняет сценарий и возвращает SQL-лог. */
    fun runExample(exampleId: String): DemoReport
}
