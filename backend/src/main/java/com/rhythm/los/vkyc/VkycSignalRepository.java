package com.rhythm.los.vkyc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VkycSignalRepository extends JpaRepository<VkycSignal, Long> {
    List<VkycSignal> findTop200BySessionIdAndSenderNotAndIdGreaterThanOrderByIdAsc(Long sessionId, String sender, Long after);
}
