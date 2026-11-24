-- Account deletion is non-destructive: the row and all clinical history stay,
-- but the account is marked DELETED so it can never sign in again. V1's
-- status check did not include DELETED, which made every admin delete fail
-- with a DataIntegrityViolationException (409 "conflicts with existing data").

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_account_status;

ALTER TABLE users
    ADD CONSTRAINT chk_account_status
    CHECK (
        account_status IN (
            'PENDING',
            'ACTIVE',
            'LOCKED',
            'SUSPENDED',
            'DISABLED',
            'DELETED'
        )
    );
