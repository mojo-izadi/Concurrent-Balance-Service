package com.example.concurrentbalanceservice.repository;

import com.example.concurrentbalanceservice.repository.entity.TransactionJpaEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface TransactionJpaRepository extends Repository<TransactionJpaEntity, String> {
	Optional<TransactionJpaEntity> findById(String transactionId);

	@Modifying
	@Query(value = """
			INSERT INTO transactions
			    (transaction_id, transaction_type, amount, status, source_account_id, destination_account_id, updated_at)
			VALUES (:transactionId, :type, :amount, 'PENDING', :sourceAccountId, :destinationAccountId, :updatedAt)
			ON CONFLICT (transaction_id) DO NOTHING
			""", nativeQuery = true)
	int insertPending(@Param("transactionId") String transactionId, @Param("type") String type,
			@Param("amount") long amount, @Param("sourceAccountId") String sourceAccountId,
			@Param("destinationAccountId") String destinationAccountId, @Param("updatedAt") Instant updatedAt);

	@Query(value = "SELECT status FROM transactions WHERE transaction_id = :transactionId", nativeQuery = true)
	Optional<String> findStatus(@Param("transactionId") String transactionId);

	@Modifying
	@Query(value = """
			UPDATE transactions
			SET status = :status, failure_reason = :failureReason, updated_at = :updatedAt
			WHERE transaction_id = :transactionId AND status = 'PENDING'
			""", nativeQuery = true)
	int updateOutcome(@Param("transactionId") String transactionId, @Param("status") String status,
			@Param("failureReason") String failureReason, @Param("updatedAt") Instant updatedAt);

	@Query(value = """
			SELECT transaction_id FROM transactions
			WHERE status = 'PENDING' AND updated_at < CURRENT_TIMESTAMP - (:ageMillis * INTERVAL '1 millisecond')
			ORDER BY updated_at, transaction_id
			""", nativeQuery = true)
	List<String> findStalePendingIds(@Param("ageMillis") long ageMillis);
}
