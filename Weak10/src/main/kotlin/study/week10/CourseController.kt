package study.week10

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1/courses")
class CourseController(private val service: CourseService) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CourseRequest) = service.create(request)

    @GetMapping
    fun findAll(@RequestParam("course_name", required = false) courseName: String?) =
        service.findAll(courseName)

    @PutMapping("/{courseId}")
    fun update(@PathVariable courseId: Long, @Valid @RequestBody request: CourseRequest) =
        service.update(courseId, request)

    @DeleteMapping("/{courseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable courseId: Long) = service.delete(courseId)
}
