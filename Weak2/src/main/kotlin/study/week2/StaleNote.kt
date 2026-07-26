// Сигнализирует о конфликте версий при конкурентном обновлении заметки.
// Компонент относится к учебному модулю недели 2 и раскрывает его основной пример.
package study.week2

import org.springframework.web.bind.annotation.*

class StaleNote : RuntimeException("Note was changed by another request")
