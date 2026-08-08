package ru.dbapp.data

import kotlinx.coroutines.runBlocking
import ru.dbapp.model.DatabaseSettings
import ru.dbapp.model.DemoCatalog
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Полный прогон обращается к реальному PostgreSQL и потому включается явным системным свойством.
 * Обычный `desktopTest` остаётся быстрым и переносимым для машины без запущенной БД.
 */
class PostgresScenarioIntegrationTest {
    /** Все кнопки выполняются последовательно; ошибки собираются, чтобы один дефект не скрыл остальные. */
    @Test
    fun `all examples run against local PostgreSQL 18`() = runBlocking {
        if (System.getProperty("dbapp.integration") != "true") return@runBlocking

        val settings = DatabaseSettings(
            url = System.getProperty("dbapp.url")
                ?: "jdbc:postgresql://localhost:5432/postgres?connectTimeout=5&ApplicationName=DBAppTest",
            user = System.getProperty("dbapp.user") ?: System.getProperty("user.name"),
            password = System.getProperty("dbapp.password") ?: "",
        )
        val runner = PostgresDemoRunner()
        val failures = mutableListOf<String>()

        runner.connect(settings)
        DemoCatalog.topics.flatMap { it.examples }.forEach { example ->
            val report = runner.runExample(example.id, settings)
            if (!report.successful) {
                failures += buildString {
                    appendLine("${example.id}: ${example.title}")
                    append(report.lines.joinToString("\n"))
                }
            }

            // Каждый сценарий обязан показывать фактически отправленный SQL и наблюдаемый ответ PostgreSQL.
            // Маркер ошибки тоже оканчивается на "SQL>", поэтому считаем только самостоятельный SQL-запрос.
            val sqlCount = report.lines.count { "] SQL>" in it }
            val resultCount = report.lines.count { " РЕЗУЛЬТАТ>" in it }
            val errorCount = report.lines.count { " ОШИБКА SQL>" in it }
            if (sqlCount == 0 || resultCount + errorCount < sqlCount) {
                failures += buildString {
                    appendLine("${example.id}: неполная SQL-трассировка")
                    appendLine("SQL=$sqlCount, результатов=$resultCount, SQL-ошибок=$errorCount")
                    append(report.lines.joinToString("\n"))
                }
            }

            val leakedSetupDdl = report.lines.any { line ->
                Regex(
                    """\bCREATE\s+(?:(?:GLOBAL|LOCAL)\s+)?(?:(?:TEMP|TEMPORARY|UNLOGGED)\s+)?(?:SCHEMA|TABLE)\b""",
                    RegexOption.IGNORE_CASE,
                ).containsMatchIn(line)
            }
            if (leakedSetupDdl) {
                failures += buildString {
                    appendLine("${example.id}: в лог попал служебный CREATE SCHEMA/TABLE")
                    append(report.lines.joinToString("\n"))
                }
            }

            // Подключение и выбор схемы выполняются в фоне и не должны отвлекать от учебного SQL.
            val leakedConnectionSetup = report.lines.any { line ->
                "Соединение открыто" in line ||
                    "Соединение закрыто" in line ||
                    Regex("""\bSET\s+(?:(?:LOCAL|SESSION)\s+)?search_path\b""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(line)
            }
            if (leakedConnectionSetup) {
                failures += buildString {
                    appendLine("${example.id}: в лог попала служебная настройка соединения")
                    append(report.lines.joinToString("\n"))
                }
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(separator = "\n\n", prefix = "Ошибки PostgreSQL-сценариев:\n"),
        )
    }
}
