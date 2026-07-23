package study.week15

import kotlinx.serialization.Serializable

@Serializable
data class TransferResult(val id: String, val status: String)
