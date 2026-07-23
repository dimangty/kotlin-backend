package study.week16

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class TransferView(val id: UUID, val status: String)
