package com.pos.auth.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.domain.Branch;
import com.pos.auth.repository.BranchRepository;
import com.pos.common.error.Errors;

import lombok.RequiredArgsConstructor;

/** Branch administration. */
@Service
@RequiredArgsConstructor
public class BranchService {

    private final BranchRepository branches;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<Branch> findAll() {
        return branches.findAll();
    }

    @Transactional(readOnly = true)
    public Branch get(UUID id) {
        return branches.findById(id).orElseThrow(() -> Errors.NotFoundException.of("Branch", id));
    }

    @Transactional
    public Branch create(String code, String name, String timezone) {
        String normalizedCode = code.trim().toUpperCase(java.util.Locale.ROOT);
        if (branches.existsByCode(normalizedCode)) {
            throw new Errors.ConflictException(
                    "branch.code_taken", "A branch with that code already exists.");
        }
        Branch branch = new Branch(normalizedCode, name.trim());
        if (timezone != null && !timezone.isBlank()) {
            branch.setTimezone(timezone);
        }
        branches.save(branch);
        audit.record(
                AuditService.BRANCH_CREATED,
                "Branch",
                branch.getId(),
                Map.of("code", normalizedCode));
        return branch;
    }

    @Transactional
    public Branch update(UUID id, String name, String timezone, Boolean active) {
        Branch branch = get(id);
        if (name != null && !name.isBlank()) {
            branch.setName(name.trim());
        }
        if (timezone != null && !timezone.isBlank()) {
            branch.setTimezone(timezone);
        }
        if (active != null) {
            branch.setActive(active);
        }
        branches.save(branch);
        audit.record(AuditService.BRANCH_UPDATED, "Branch", id, null);
        return branch;
    }
}
