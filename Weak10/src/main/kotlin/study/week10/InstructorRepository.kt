package study.week10

import org.springframework.data.jpa.repository.JpaRepository

interface InstructorRepository : JpaRepository<Instructor, Long>
