package study.week10

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional

class CourseServiceTest {
    private val courseRepository = mock(CourseRepository::class.java)
    private val instructorRepository = mock(InstructorRepository::class.java)
    private val instructorService = InstructorService(instructorRepository)
    private val service = CourseService(courseRepository, instructorService)

    @Test
    fun `create links a course to an existing instructor`() {
        val instructor = Instructor(id = 7, name = "Dilip Sundarraj")
        `when`(instructorRepository.findById(7)).thenReturn(Optional.of(instructor))
        `when`(courseRepository.save(any(Course::class.java))).thenAnswer { invocation ->
            invocation.getArgument<Course>(0).also { it.id = 42 }
        }

        val result = service.create(CourseRequest(" Kotlin ", " Development ", 7))

        assertEquals(CourseResponse(42, "Kotlin", "Development", 7, "Dilip Sundarraj"), result)
        verify(courseRepository).save(any(Course::class.java))
    }

    @Test
    fun `create rejects an unknown instructor before saving`() {
        `when`(instructorRepository.findById(99)).thenReturn(Optional.empty())

        assertThrows(InstructorNotFound::class.java) {
            service.create(CourseRequest("Kotlin", "Development", 99))
        }

        verify(courseRepository, never()).save(any(Course::class.java))
    }
}
