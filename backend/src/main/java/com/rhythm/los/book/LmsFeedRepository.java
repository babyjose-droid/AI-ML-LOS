package com.rhythm.los.book;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LmsFeedRepository extends JpaRepository<LmsFeed, Long> {
    List<LmsFeed> findTop20ByOrderByIdDesc();
}
