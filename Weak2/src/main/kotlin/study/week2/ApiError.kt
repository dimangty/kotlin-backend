package study.week2


data class ApiError(val code: String, val message: String, val details: Map<String, String>, val requestId: String)
