package com.pos.purchasing.repository;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.pos.purchasing.domain.Expense;
import com.pos.purchasing.domain.ExpenseStatus;

/**
 * A path per filter rather than one query with {@code :status IS NULL OR ...}: an untyped null
 * parameter makes PostgreSQL reject the statement.
 */
public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    Page<Expense> findByBranchIdAndIncurredOnBetween(
            UUID branchId, LocalDate from, LocalDate to, Pageable pageable);

    Page<Expense> findByBranchIdAndIncurredOnBetweenAndStatus(
            UUID branchId, LocalDate from, LocalDate to, ExpenseStatus status, Pageable pageable);

    /** Head office's: no branch. */
    Page<Expense> findByBranchIdIsNullAndIncurredOnBetween(
            LocalDate from, LocalDate to, Pageable pageable);

    Page<Expense> findByBranchIdIsNullAndIncurredOnBetweenAndStatus(
            LocalDate from, LocalDate to, ExpenseStatus status, Pageable pageable);

    @Query(
            value =
                    """
                    SELECT COALESCE(MAX(CAST(split_part(expense_number, '-', 3) AS INTEGER)), 0)
                    FROM expenses
                    WHERE expense_number LIKE :prefix || '%'
                    """,
            nativeQuery = true)
    int highestSequenceFor(String prefix);
}
