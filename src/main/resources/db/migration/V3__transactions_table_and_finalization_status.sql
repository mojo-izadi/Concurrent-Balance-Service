BEGIN;

ALTER TABLE public.balance_transactions RENAME TO transactions;

ALTER TABLE public.transactions RENAME CONSTRAINT balance_transactions_account_shape
    TO transactions_account_shape;
ALTER TABLE public.transactions RENAME CONSTRAINT balance_transactions_amount_not_null
    TO transactions_amount_not_null;
ALTER TABLE public.transactions RENAME CONSTRAINT balance_transactions_amount_positive
    TO transactions_amount_positive;
ALTER TABLE public.transactions RENAME CONSTRAINT balance_transactions_destination_account_fk
    TO transactions_destination_account_fk;
ALTER TABLE public.transactions RENAME CONSTRAINT balance_transactions_failure_consistent
    TO transactions_failure_consistent;
ALTER TABLE public.transactions RENAME CONSTRAINT balance_transactions_pkey
    TO transactions_pkey;
ALTER TABLE public.transactions RENAME CONSTRAINT balance_transactions_source_account_fk
    TO transactions_source_account_fk;
ALTER TABLE public.transactions RENAME CONSTRAINT balance_transactions_status_not_null
    TO transactions_status_not_null;
ALTER TABLE public.transactions RENAME CONSTRAINT balance_transactions_status_valid
    TO transactions_status_valid;
ALTER TABLE public.transactions RENAME CONSTRAINT balance_transactions_transaction_id_not_null
    TO transactions_transaction_id_not_null;
ALTER TABLE public.transactions RENAME CONSTRAINT balance_transactions_transaction_type_not_null
    TO transactions_transaction_type_not_null;
ALTER TABLE public.transactions RENAME CONSTRAINT balance_transactions_transaction_type_valid
    TO transactions_transaction_type_valid;
ALTER TABLE public.transactions RENAME CONSTRAINT balance_transactions_updated_at_not_null
    TO transactions_updated_at_not_null;

ALTER INDEX public.balance_transactions_pending_updated_idx
    RENAME TO transactions_pending_updated_idx;

ALTER TABLE public.transactions DROP CONSTRAINT transactions_status_valid;
UPDATE public.transactions SET status = 'FINALIZED' WHERE status = 'FINISHED';
UPDATE public.transactions SET failure_reason = 'FINALIZATION_ERROR' WHERE failure_reason = 'EXECUTION_ERROR';
ALTER TABLE public.transactions ADD CONSTRAINT transactions_status_valid
    CHECK (status IN ('PENDING', 'FINALIZED', 'FAILED'));

COMMIT;
