package com.seatlock.common.persistence;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.Hibernate;

/**
 * Common ID mapping + JPA-safe equality: equal only if same class and same non-null ID.
 * hashCode is per-class constant so entities stay findable in hash sets after being persisted.
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    public Long getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        // Use the getter: on a lazy proxy the field itself is not initialized.
        Long otherId = ((BaseEntity) o).getId();
        return getId() != null && getId().equals(otherId);
    }

    @Override
    public int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}