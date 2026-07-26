// Описывает тело ответа echo-эндпоинта.
// Компонент относится к учебному модулю недели 1 и раскрывает его основной пример.
package study.week1


data class EchoResponse(val message: String, val requestId: String?)
