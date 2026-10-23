CREATE TABLE IF NOT EXISTS accounts (
    account_id VARCHAR(128) PRIMARY KEY,
    balance BIGINT NOT NULL
);

INSERT INTO accounts (account_id, balance) VALUES
    ('A', 10000),
    ('B', 10000),
    ('C', 10000)
ON CONFLICT (account_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS balance_operations (
    transaction_id VARCHAR(128) PRIMARY KEY,
    operation_type VARCHAR(16) NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    source_account_id VARCHAR(128),
    destination_account_id VARCHAR(128),
    failure_reason VARCHAR(64),
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT balance_operations_amount_positive CHECK (amount > 0),
    CONSTRAINT balance_operations_status_valid CHECK (status IN ('PENDING', 'FINISHED', 'FAILED')),
    CONSTRAINT balance_operations_type_valid CHECK (operation_type IN ('CREDIT', 'DEBIT', 'TRANSFER')),
    CONSTRAINT balance_operations_failure_consistent CHECK
        ((status = 'FAILED' AND failure_reason IS NOT NULL) OR
         (status <> 'FAILED' AND failure_reason IS NULL)),
    CONSTRAINT balance_operations_account_shape CHECK (
        (operation_type = 'CREDIT' AND source_account_id IS NULL AND destination_account_id IS NOT NULL) OR
        (operation_type = 'DEBIT' AND source_account_id IS NOT NULL AND destination_account_id IS NULL) OR
        (operation_type = 'TRANSFER' AND source_account_id IS NOT NULL AND destination_account_id IS NOT NULL
            AND source_account_id <> destination_account_id)
    ),
    CONSTRAINT balance_operations_source_account_fk FOREIGN KEY (source_account_id)
        REFERENCES accounts(account_id),
    CONSTRAINT balance_operations_destination_account_fk FOREIGN KEY (destination_account_id)
        REFERENCES accounts(account_id)
);

CREATE INDEX IF NOT EXISTS balance_operations_pending_updated_idx
    ON balance_operations (updated_at, transaction_id)
    WHERE status = 'PENDING';
