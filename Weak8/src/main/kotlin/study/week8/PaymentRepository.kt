// Объявляет операции хранения и поиска платежей.
// Компонент относится к учебному модулю недели 8 и раскрывает его основной пример.
package study.week8

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PaymentRepository : JpaRepository<Payment, UUID>
