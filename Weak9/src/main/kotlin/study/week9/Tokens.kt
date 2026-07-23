package study.week9

import org.springframework.web.bind.annotation.*

data class Tokens(val accessToken: String, val refreshToken: String)
