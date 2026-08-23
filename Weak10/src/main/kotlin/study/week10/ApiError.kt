package study.week10

data class ApiError(
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
    val requestId: String,
)
