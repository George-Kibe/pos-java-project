package com.pos.sales.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.sales.domain.Register;
import com.pos.sales.repository.RegisterRepository;

import lombok.RequiredArgsConstructor;

/**
 * Tills and their numbers. A device becomes the branch's next till the first time it opens a shift
 * there; a manager may rename or renumber it afterwards.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegisterService {

    private final RegisterRepository registers;
    private final JdbcClient jdbc;

    /**
     * The till for this device at this branch, numbered now if it is new. Two devices opening at
     * once must not take the same number, so numbering holds a lock per branch for the transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Register ensure(UUID branchId, UUID registerId) {
        var existing = registers.findById(registerId);
        if (existing.isPresent()) {
            if (!existing.get().getBranchId().equals(branchId)) {
                throw new Errors.ConflictException(
                        "register.other_branch",
                        "This device is %s at another branch. Use it there, or reset it."
                                .formatted(existing.get().label()));
            }
            return existing.get();
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:key))")
                .param("key", "registers:" + branchId)
                .query()
                .listOfRows();
        int next = registers.highestNumber(branchId) + 1;
        return registers.save(new Register(registerId, branchId, next));
    }

    public java.util.List<Register> atBranch(UUID branchId) {
        return registers.findByBranchIdOrderByNumberAsc(branchId);
    }

    public Register require(UUID id) {
        return registers.findById(id).orElseThrow(() -> Errors.NotFoundException.of("Till", id));
    }

    /** Tills by id, for labelling a page of shifts. */
    public Map<UUID, Register> byIds(Collection<UUID> ids) {
        return registers.findAllById(ids).stream()
                .collect(Collectors.toMap(Register::getId, Function.identity()));
    }

    @Transactional
    public Register update(UUID id, Integer number, String name) {
        Register register = require(id);
        if (number != null && number != register.getNumber()) {
            if (number < 1) {
                throw new Errors.BusinessRuleException(
                        "register.invalid_number", "A till number starts at 1.");
            }
            registers
                    .findByBranchIdAndNumber(register.getBranchId(), number)
                    .ifPresent(
                            taken -> {
                                throw new Errors.ConflictException(
                                        "register.number_taken",
                                        "%s already has number %d."
                                                .formatted(taken.label(), number));
                            });
            register.setNumber(number);
        }
        if (name != null) {
            register.setName(name.isBlank() ? null : name.trim());
        }
        return registers.save(register);
    }
}
