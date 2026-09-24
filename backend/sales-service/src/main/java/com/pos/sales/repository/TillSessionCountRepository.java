package com.pos.sales.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.sales.domain.cash.TillSessionCount;

public interface TillSessionCountRepository extends JpaRepository<TillSessionCount, UUID> {

    List<TillSessionCount> findByTillSessionId(UUID tillSessionId);
}
