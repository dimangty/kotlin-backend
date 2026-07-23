package study.week11


interface ExternalGateway { suspend fun charge(key: String, amountMinor: Long): String }
