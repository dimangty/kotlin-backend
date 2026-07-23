package study.week9

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class User(val id: UUID, val email: String, val passwordHash: String)
