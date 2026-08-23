CREATE TABLE instructors (
    id bigserial PRIMARY KEY,
    name varchar(120) NOT NULL CHECK (btrim(name) <> '')
);

CREATE TABLE courses (
    id bigserial PRIMARY KEY,
    name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    category varchar(100) NOT NULL CHECK (btrim(category) <> ''),
    instructor_id bigint NOT NULL REFERENCES instructors(id) ON DELETE RESTRICT
);

CREATE INDEX courses_instructor_id_idx ON courses(instructor_id);
