# DBApp

Учебное desktop-приложение на Compose Multiplatform для живых экспериментов с PostgreSQL 18. Содержание и названия примеров взяты из файла `konspekt-s-rekomendaciyami-po-resheniyu-problem (2).pdf` в корне репозитория.

Приложение открывает шесть тематических экранов:

- ACID;
- Аномалии;
- Уровни изоляции;
- Блокировки;
- Дедлоки;
- Индексы.

На каждом экране есть кнопка «Назад», список исполняемых примеров и read-only поле с пошаговым логом SQL, результатов, SQLSTATE и планов `EXPLAIN (ANALYZE, BUFFERS)`.

### Формат подробного лога

Каждая JDBC-сессия получает понятное имя (`dirty-A`, `deadlock-B`, `index-gin-jsonb` и другие). Для каждой операции приложение показывает:

- открытие, настройки и закрытие соединения;
- фактически отправленный SQL;
- строки результата или scalar-значение;
- количество изменённых строк для DML/DDL;
- полный план `EXPLAIN (ANALYZE, BUFFERS)`;
- `COMMIT`, `ROLLBACK`, `SAVEPOINT` и `ROLLBACK TO SAVEPOINT`;
- текст ошибки и SQLSTATE для ожидаемых и неожиданных конфликтов.

Служебные `CREATE SCHEMA`, `CREATE TABLE`, `SET search_path`, открытие и закрытие JDBC-соединений выполняются, но намеренно скрыты из лога. `CREATE INDEX` остаётся видимым как часть учебных экспериментов.

Пример:

```text
[12:30:15.120] [non-repeatable-A] SQL>
    SELECT balance FROM accounts WHERE owner = 'Alice'
[12:30:15.122] [non-repeatable-A] РЕЗУЛЬТАТ>
    Значение: 1000.00
```

## Безопасность данных

DBApp создаёт и изменяет только отдельную схему `dbapp_lab` в выбранной базе. Существующие пользовательские схемы не удаляются и не изменяются. Большая таблица `orders` на 200 000 строк создаётся лениво при первом индексном эксперименте.

Все примеры рассчитаны на локальный учебный сервер. Не указывайте в форме подключения production-базу.

## Подготовка PostgreSQL 18 из Homebrew

Проверьте установку и запустите сервис:

```bash
/opt/homebrew/opt/postgresql@18/bin/psql --version
brew services start postgresql@18
/opt/homebrew/opt/postgresql@18/bin/pg_isready
```

Homebrew по умолчанию обычно создаёт роль с именем пользователя macOS и разрешает локальное подключение без пароля. Стартовые значения DBApp:

```text
URL:  jdbc:postgresql://localhost:5432/postgres?connectTimeout=5&ApplicationName=DBApp
User: имя текущего пользователя macOS
Pass: пустой
```

Параметры можно изменить в форме либо задать переменными окружения:

```bash
export DBAPP_DB_URL='jdbc:postgresql://localhost:5432/postgres'
export DBAPP_DB_USER='postgres'
export DBAPP_DB_PASSWORD='secret'
```

## Запуск

```bash
./gradlew run
```

Тесты и сборка:

```bash
./gradlew desktopTest
./gradlew build
```

## Структура проекта

- `src/commonMain` — модели, каталог примеров и адаптивный Compose UI;
- `src/desktopMain` — точка входа, JDBC-инфраструктура и реальные сценарии PostgreSQL;
- `src/commonTest` — инварианты каталога экранов;
- `src/desktopTest` — соответствие каждой UI-кнопки JDBC-обработчику.

Прямой JDBC здесь выбран намеренно: уровни изоляции, границы транзакций, две сессии и SQLSTATE видны без скрытого поведения ORM.
