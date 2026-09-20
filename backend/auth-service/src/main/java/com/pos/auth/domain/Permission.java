package com.pos.auth.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.pos.common.id.UuidV7;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One thing a user may do, e.g. {@code sale:void}.
 *
 * <p>The vocabulary is fixed in code, because endpoints are written against these strings. Adding a
 * permission is a code change; bundling permissions into a new role is not.
 *
 * <p>Deliberately not a {@link BaseEntity}: this is reference data seeded by migration, with no
 * meaningful author or edit history.
 */
@Entity
@Table(name = "permissions")
@Getter
@Setter
@NoArgsConstructor
public class Permission {

    @Id private UUID id = UuidV7.randomUUID();

    @Column(nullable = false, length = 100, unique = true)
    private String code;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, length = 255)
    private String description;

    @Override
    public boolean equals(Object other) {
        return other instanceof Permission p && id != null && id.equals(p.id);
    }

    @Override
    public int hashCode() {
        return Permission.class.hashCode();
    }
}
