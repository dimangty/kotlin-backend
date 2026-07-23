package study.week11

import kotlinx.coroutines.runBlocking

fun main() = runBlocking { println(PaymentCoordinator(MemoryRepository(), DemoGateway()).pay("demo", 100)) }
