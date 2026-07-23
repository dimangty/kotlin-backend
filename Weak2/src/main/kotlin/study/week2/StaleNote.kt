package study.week2

import org.springframework.web.bind.annotation.*

class StaleNote : RuntimeException("Note was changed by another request")
