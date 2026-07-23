package study.week15

import kotlinx.serialization.Serializable

@Serializable
data class TransferRequest(val from: String, val to: String, val amountMinor: Long)
