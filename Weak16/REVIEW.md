# Review

Статус: конкурентный Testcontainers test проходит. Два одновременных retry дают один ID; transfers=1, ledger entries=2, ledger net=0, audit events=1, total balance=2000. Другой payload с тем же key отклоняется.

Это сильный учебный baseline, но не production-ready: отсутствуют auth/ownership, error mapping, cursor pagination, bounded deadlock/serialization retry и 50-operation concurrency/rollback suite.
