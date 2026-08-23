package study.week10

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class InstructorService(private val repository: InstructorRepository) {
    @Transactional
    fun create(request: InstructorRequest): InstructorResponse {
        val saved = repository.save(Instructor(name = request.name.trim()))
        return InstructorResponse(saved.id!!, saved.name)
    }

    @Transactional(readOnly = true)
    fun require(id: Long): Instructor = repository.findById(id).orElseThrow { InstructorNotFound(id) }
}
