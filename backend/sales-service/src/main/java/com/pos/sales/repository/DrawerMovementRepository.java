package com.pos.sales.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.sales.domain.cash.DrawerMovement;

public interface DrawerMovementRepository extends JpaRepository<DrawerMovement, UUID> {

    /** What a drawer holds: every movement summed per denomination. */
    @Query(
            """
            SELECT m.denomination AS denomination, SUM(m.count) AS count
            FROM DrawerMovement m WHERE m.tillSessionId = :sessionId
            GROUP BY m.denomination
            """)
    List<Holding> holdings(@Param("sessionId") UUID sessionId);

    interface Holding {
        BigDecimal getDenomination();

        Long getCount();
    }
}
