package com.paymentapi.financialcore.repository;

import com.paymentapi.financialcore.entity.Account;
import com.paymentapi.financialcore.entity.Account.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByExternalRefAndAccountType(String externalRef, AccountType type);
}
