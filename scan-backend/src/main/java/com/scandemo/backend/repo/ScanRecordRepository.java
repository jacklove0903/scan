package com.scandemo.backend.repo;

import com.scandemo.backend.domain.RecordStatus;
import com.scandemo.backend.domain.ScanRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScanRecordRepository extends JpaRepository<ScanRecord, Long> {

    /** 按账号 + 状态取记录，按采集编号排序（推送时合并箱型用） */
    List<ScanRecord> findByAccountIdAndStatusOrderByNoAscIdAsc(Long accountId, RecordStatus status);

    List<ScanRecord> findByAccountIdOrderByIdDesc(Long accountId);

    long countByAccountIdAndStatus(Long accountId, RecordStatus status);

    Optional<ScanRecord> findByIdAndAccountId(Long id, Long accountId);

    @Modifying
    @Query("update ScanRecord r set r.accountId = :accountId where r.accountId is null")
    int assignAccountToOrphans(@Param("accountId") Long accountId);
}
