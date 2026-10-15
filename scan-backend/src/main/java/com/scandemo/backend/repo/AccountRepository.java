package com.scandemo.backend.repo;

import com.scandemo.backend.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByLoginName(String loginName);

    List<Account> findAllByOrderByIdAsc();
}
