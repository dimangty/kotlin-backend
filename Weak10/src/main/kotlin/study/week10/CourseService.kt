package study.week10

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CourseService(
    private val courseRepository: CourseRepository,
    private val instructorService: InstructorService,
) {
    @Transactional
    fun create(request: CourseRequest): CourseResponse {
        val instructor = instructorService.require(request.instructorId)
        val course = Course(
            name = request.name.trim(),
            category = request.category.trim(),
            instructor = instructor,
        )
        return courseRepository.save(course).toResponse()
    }

    @Transactional(readOnly = true)
    fun findAll(courseName: String?): List<CourseResponse> {
        val filter = courseName?.trim()?.takeIf { it.isNotEmpty() }
        val courses = if (filter == null) {
            courseRepository.findAllByOrderByNameAsc()
        } else {
            courseRepository.findAllByNameContainingIgnoreCaseOrderByNameAsc(filter)
        }
        return courses.map { it.toResponse() }
    }

    @Transactional
    fun update(id: Long, request: CourseRequest): CourseResponse {
        val course = courseRepository.findById(id).orElseThrow { CourseNotFound(id) }
        val instructor = instructorService.require(request.instructorId)
        course.name = request.name.trim()
        course.category = request.category.trim()
        course.instructor = instructor
        return course.toResponse()
    }

    @Transactional
    fun delete(id: Long) {
        val course = courseRepository.findById(id).orElseThrow { CourseNotFound(id) }
        courseRepository.delete(course)
    }

    private fun Course.toResponse() = CourseResponse(
        id = requireNotNull(id),
        name = name,
        category = category,
        instructorId = requireNotNull(instructor.id),
        instructorName = instructor.name,
    )
}
