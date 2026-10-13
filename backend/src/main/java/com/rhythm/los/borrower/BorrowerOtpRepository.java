package com.rhythm.los.borrower;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface BorrowerOtpRepository extends JpaRepository<BorrowerOtp, Long> {
    Optional<BorrowerOtp> findFirstByMobileOrderByIdDesc(String mobile);
    long countByMobileAndCreatedAtAfter(String mobile, Instant after);
}
