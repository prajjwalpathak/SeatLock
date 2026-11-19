package com.seatlock.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** JPA-safe equality: same class and same non-null id; hashCode constant per class (stable across persist). */
class BaseEntityTest {

    static class Apple extends BaseEntity {}

    static class Pear extends BaseEntity {}

    private static <T extends BaseEntity> T withId(T entity, Long id) {
        ReflectionTestUtils.setField(entity, BaseEntity.class, "id", id, Long.class);
        return entity;
    }

    @Test
    void sameIdAndClassAreEqual() {
        assertThat(withId(new Apple(), 1L)).isEqualTo(withId(new Apple(), 1L));
    }

    @Test
    void differentIdsOrClassesAreNotEqual() {
        assertThat(withId(new Apple(), 1L)).isNotEqualTo(withId(new Apple(), 2L));
        assertThat(withId(new Apple(), 1L)).isNotEqualTo(withId(new Pear(), 1L));
        assertThat(withId(new Apple(), 1L)).isNotEqualTo(null);
        assertThat(withId(new Apple(), 1L)).isNotEqualTo("an apple");
    }

    @Test
    void unsavedEntitiesAreOnlyEqualToThemselves() {
        Apple unsaved = new Apple();
        assertThat(unsaved).isEqualTo(unsaved);
        assertThat(unsaved).isNotEqualTo(new Apple());          // both ids null: two different rows-to-be
        assertThat(unsaved).isNotEqualTo(withId(new Apple(), 1L));
        assertThat(withId(new Apple(), 1L)).isNotEqualTo(unsaved);
    }

    @Test
    void hashCodeIsPerClassAndSurvivesGettingAnId() {
        Apple apple = new Apple();
        int before = apple.hashCode();
        withId(apple, 42L);
        assertThat(apple.hashCode()).isEqualTo(before).isEqualTo(new Apple().hashCode());
    }
}
