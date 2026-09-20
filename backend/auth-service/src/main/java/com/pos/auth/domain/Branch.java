package com.pos.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An outlet. Users are scoped to the branches they may act in. */
@Entity
@Table(name = "branches")
@Getter
@Setter
@NoArgsConstructor
public class Branch extends BaseEntity {

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 64)
    private String timezone = "Africa/Nairobi";

    @Column(nullable = false)
    private boolean active = true;

    public Branch(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
