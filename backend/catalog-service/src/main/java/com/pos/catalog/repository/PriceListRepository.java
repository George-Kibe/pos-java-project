package com.pos.catalog.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.catalog.domain.PriceList;

public interface PriceListRepository extends JpaRepository<PriceList, UUID> {

    Optional<PriceList> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Lists that could apply to a branch: the branch's own, plus the chain-wide ones.
     *
     * <p>Highest priority first, then code, so the winner is the same on every till.
     */
    List<PriceList> findByActiveTrueAndBranchIdInOrderByPriorityDescCodeAsc(List<UUID> branchIds);

    List<PriceList> findByActiveTrueAndBranchIdIsNullOrderByPriorityDescCodeAsc();
}
