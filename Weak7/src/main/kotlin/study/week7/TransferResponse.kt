package study.week7

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class TransferResponse(val id: UUID, val status: String)
