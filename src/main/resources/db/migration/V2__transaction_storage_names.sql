BEGIN;

ALTER TABLE public.balance_operations RENAME TO balance_transactions;
ALTER TABLE public.balance_transactions RENAME COLUMN operation_type TO transaction_type;

ALTER TABLE public.balance_transactions RENAME CONSTRAINT balance_operations_account_shape
    TO balance_transactions_account_shape;
ALTER TABLE public.balance_transactions RENAME CONSTRAINT balance_operations_amount_not_null
    TO balance_transactions_amount_not_null;
ALTER TABLE public.balance_transactions RENAME CONSTRAINT balance_operations_amount_positive
    TO balance_transactions_amount_positive;
ALTER TABLE public.balance_transactions RENAME CONSTRAINT balance_operations_destination_account_fk
    TO balance_transactions_destination_account_fk;
ALTER TABLE public.balance_transactions RENAME CONSTRAINT balance_operations_failure_consistent
    TO balance_transactions_failure_consistent;
ALTER TABLE public.balance_transactions RENAME CONSTRAINT balance_operations_operation_type_not_null
    TO balance_transactions_transaction_type_not_null;
ALTER TABLE public.balance_transactions RENAME CONSTRAINT balance_operations_pkey
    TO balance_transactions_pkey;
ALTER TABLE public.balance_transactions RENAME CONSTRAINT balance_operations_source_account_fk
    TO balance_transactions_source_account_fk;
ALTER TABLE public.balance_transactions RENAME CONSTRAINT balance_operations_status_not_null
    TO balance_transactions_status_not_null;
ALTER TABLE public.balance_transactions RENAME CONSTRAINT balance_operations_status_valid
    TO balance_transactions_status_valid;
ALTER TABLE public.balance_transactions RENAME CONSTRAINT balance_operations_transaction_id_not_null
    TO balance_transactions_transaction_id_not_null;
ALTER TABLE public.balance_transactions RENAME CONSTRAINT balance_operations_type_valid
    TO balance_transactions_transaction_type_valid;
ALTER TABLE public.balance_transactions RENAME CONSTRAINT balance_operations_updated_at_not_null
    TO balance_transactions_updated_at_not_null;

ALTER INDEX public.balance_operations_pending_updated_idx
    RENAME TO balance_transactions_pending_updated_idx;

COMMIT;
