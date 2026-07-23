package study.week15

import kotlinx.serialization.Serializable

@Serializable
data class ApiError(val code: String, val message: String)
