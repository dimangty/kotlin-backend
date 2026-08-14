package ru.dbapp.data

/** Реестр централизованно связывает все 44 идентификатора UI с исполняемыми JDBC-сценариями. */
internal object ScenarioRegistry {
    val scenarios: Map<String, DemoScenario> = buildMap {
        putAll(TransactionScenarios.scenarios)
        putAll(LockScenarios.scenarios)
        putAll(IndexScenarios.scenarios)
    }

    /** Набор используется контрактным тестом клиента и бэкенда. */
    val supportedExampleIds: Set<String>
        get() = scenarios.keys
}
