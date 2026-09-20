package com.pos.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class BaseEntityTest {

    static class Product extends BaseEntity {
        String name;

        Product(String name) {
            this.name = name;
        }

        Product(UUID id, String name) {
            this.name = name;
            setId(id);
        }
    }

    static class Branch extends BaseEntity {}

    @Test
    void anIdIsAssignedOnConstructionNotByTheDatabase() {
        // An offline till must be able to reference a sale before it ever reaches Postgres.
        Product product = new Product("Milk");
        assertThat(product.getId()).isNotNull();
        assertThat(product.getId().version()).isEqualTo(7);
    }

    @Test
    void identityIsTheIdAloneSoEditingAFieldDoesNotChangeEquality() {
        UUID id = UUID.randomUUID();
        Product one = new Product(id, "Milk");
        Product two = new Product(id, "Milk 500ml"); // renamed, same row

        assertThat(one).isEqualTo(two);
        assertThat(one).isEqualTo(one);
    }

    @Test
    void differentIdsAreDifferentEntities() {
        assertThat(new Product("Milk")).isNotEqualTo(new Product("Milk"));
        assertThat(new Product("Milk")).isNotEqualTo("not an entity");
        assertThat(new Product("Milk")).isNotEqualTo(null);
    }

    @Test
    void anEntityKeepsItsHashBucketAcrossMutation() {
        // A stable hash is why an entity can be put in a Set before and after a flush.
        Product product = new Product("Milk");
        Set<BaseEntity> set = new HashSet<>();
        set.add(product);
        product.name = "Milk 500ml";

        assertThat(set).contains(product);
    }

    @Test
    void auditFieldsStartUnsetUntilThePersistenceLayerFillsThem() {
        Product product = new Product("Milk");
        assertThat(product.getCreatedAt()).isNull();
        assertThat(product.getUpdatedAt()).isNull();
        assertThat(product.getCreatedBy()).isNull();
        assertThat(product.getUpdatedBy()).isNull();
        assertThat(product.getVersion()).isZero();
    }

    @Test
    void toStringNamesTheTypeAndId() {
        Branch branch = new Branch();
        assertThat(branch.toString()).startsWith("Branch(").contains(branch.getId().toString());
    }
}
