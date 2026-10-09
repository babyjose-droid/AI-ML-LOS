package com.rhythm.los.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, Long> {
    List<LoanApplication> findAllByOrderByIdDesc();

    @Query("select count(a) from LoanApplication a where a.pan = :pan and a.id <> :id and a.appState not in ('REJECTED','WITHDRAWN','DISBURSED')")
    long countActiveByPanExcluding(@Param("pan") String pan, @Param("id") Long id);

    @Query("select count(a) from LoanApplication a where a.mobile = :mobile and a.pan <> :pan")
    long countOtherPansWithMobile(@Param("mobile") String mobile, @Param("pan") String pan);

    @Query("select count(a) from LoanApplication a where a.pan = :pan and a.createdAt > :since")
    long countByPanSince(@Param("pan") String pan, @Param("since") Instant since);
}
