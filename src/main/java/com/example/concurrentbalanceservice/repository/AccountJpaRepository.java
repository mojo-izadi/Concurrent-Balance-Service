package com.example.concurrentbalanceservice.repository;

import com.example.concurrentbalanceservice.repository.entity.AccountJpaEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface AccountJpaRepository extends Repository<AccountJpaEntity, String> {
	boolean existsById(String accountId);

	Optional<AccountJpaEntity> findById(String accountId);

	// Balance writers exclude each other without blocking the foreign-key checks of new admissions.
	@Query(value = "SELECT balance FROM accounts WHERE account_id = :accountId FOR NO KEY UPDATE", nativeQuery = true)
	Optional<Long> lockBalanceForFinalization(@Param("accountId") String accountId);

	@Modifying
	@Query(value = "UPDATE accounts SET balance = :balance WHERE account_id = :accountId", nativeQuery = true)
	int updateBalance(@Param("accountId") String accountId, @Param("balance") long balance);
}
