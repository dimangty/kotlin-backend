# Review

Статус: schema/session scripts задают пошаговые пары для non-repeatable read, lost update, atomic fix, stable Repeatable Read snapshot и Serializable conflict; начальный invariant: 2 accounts, total=2000.

Non-repeatable read, lost update и serialization failure оставлены ручной двухсессионной лабораторией. Результат недели засчитывается только после собственного transaction timeline.
