package study.week16

import org.springframework.web.bind.annotation.*

data class ApiError(val code: String, val message: String)
