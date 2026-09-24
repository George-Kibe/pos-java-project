package com.pos.sales.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A till. Its id is the device's register id, so a device keeps its number across shifts; the
 * number is per branch, given in order of first use.
 */
@Entity
@Table(name = "registers")
@Getter
@Setter
@NoArgsConstructor
public class Register extends BaseEntity {

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(nullable = false)
    private int number;

    @Column(length = 60)
    private String name;

    public Register(UUID id, UUID branchId, int number) {
        setId(id);
        this.branchId = branchId;
        this.number = number;
    }

    /** What people call it: its name if it has one, else "Till 3". */
    public String label() {
        return name != null && !name.isBlank() ? name : "Till " + number;
    }
}
