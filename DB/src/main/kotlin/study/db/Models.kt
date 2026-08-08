package study.db

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.util.UUID

data class CreateAccountRequest(
    @field:NotBlank val ownerName: String,
    @field:Min(0) val initialBalanceMinor: Long,
)

data class AccountView(
    val id: UUID,
    val ownerName: String,
    val balanceMinor: Long,
    val version: Long,
)

data class TransferRequest(
    val fromAccountId: UUID,
    val toAccountId: UUID,
    @field:Positive val amountMinor: Long,
    // true нужен только для учебной проверки отката ACID.
    val failAfterDebit: Boolean = false,
)

data class TransferView(val id: UUID, val status: String)

enum class DemoIsolation { READ_COMMITTED, REPEATABLE_READ, SERIALIZABLE }

data class IsolationObservation(
    val isolation: DemoIsolation,
    val firstBalanceMinor: Long,
    val secondBalanceMinor: Long,
    val changedInsideTransaction: Boolean,
)

data class BalanceChangeRequest(
    val accountId: UUID,
    val deltaMinor: Long,
    @field:Min(0) @field:Max(15_000) val pauseMillis: Long = 0,
)

data class HoldLockRequest(
    val accountId: UUID,
    @field:Min(0) @field:Max(15_000) val holdMillis: Long,
)

data class GeneratePaymentsRequest(
    @field:Min(1) @field:Max(1_000_000) val count: Int,
)

data class PaymentView(
    val id: Long,
    val userId: Long,
    val reference: String,
    val status: String,
    val amountMinor: Long,
    val createdAt: Instant,
)

data class IndexView(val name: String, val definition: String)

data class JobView(val id: Long, val payload: String, val status: String)

data class ApiError(val code: String, val message: String)
