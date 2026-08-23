package study.week10

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface CourseRepository : JpaRepository<Course, Long> {
    @EntityGraph(attributePaths = ["instructor"])
    fun findAllByOrderByNameAsc(): List<Course>

    @EntityGraph(attributePaths = ["instructor"])
    fun findAllByNameContainingIgnoreCaseOrderByNameAsc(courseName: String): List<Course>
}
