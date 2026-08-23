package study.week10

class InstructorNotFound(id: Long) : RuntimeException("Instructor $id not found")

class CourseNotFound(id: Long) : RuntimeException("Course $id not found")
