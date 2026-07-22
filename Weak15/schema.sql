CREATE TABLE accounts(id uuid PRIMARY KEY, balance_minor bigint NOT NULL CHECK(balance_minor >= 0));

