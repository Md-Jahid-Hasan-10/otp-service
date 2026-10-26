package com.banking.otp.repository;

import com.banking.otp.entity.OtpRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface OtpRecordRepository extends JpaRepository<OtpRecord, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OtpRecord o where o.id = :id")
    Optional<OtpRecord> findByIdForUpdate(@Param("id") UUID id);

    Optional<OtpRecord> findTopByUserIdAndPurposeAndReferenceIdOrderByCreatedAtDesc(
            Long userId, String purpose, String referenceId);

    @Transactional
    @Modifying
    @Query("update OtpRecord o set o.used = true "
            + "where o.userId = :userId and o.purpose = :purpose "
            + "and o.referenceId = :referenceId and o.used = false")
    int invalidateActive(@Param("userId") Long userId,
                         @Param("purpose") String purpose,
                         @Param("referenceId") String referenceId);
}