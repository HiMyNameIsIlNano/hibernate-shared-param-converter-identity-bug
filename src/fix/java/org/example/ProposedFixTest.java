package org.example;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Demonstrates that the proposed one-line change actually fixes the bug.
 * <p>
 * {@code src/test/java/org/hibernate/query/internal/QueryParameterBindingImpl.java} is a verbatim copy of the class
 * from Hibernate 7.4.5.Final with a single line changed in {@code areTypesCompatible}:
 *
 * <pre>
 * - && jdbcMapping1.getValueConverter() == jdbcMapping2.getValueConverter();
 * + && Objects.equals( jdbcMapping1.getValueConverter(), jdbcMapping2.getValueConverter() );
 * </pre>
 *
 * Because it sits on the test classpath under the same fully-qualified name, it shadows the one from the jar. The very
 * queries asserted to throw in {@link SharedParameterAcrossConvertedAttributesTest} now succeed and return the correct
 * row.
 * <p>
 * Delete this file (and the patched copy) to observe the original failure again.
 */
class ProposedFixTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2023-05-03T00:00+02:00");

    private static EntityManagerFactory factory;

    @BeforeAll
    static void startUp() {
        factory = Persistence.createEntityManagerFactory("reproducer");

        try (var entityManager = factory.createEntityManager()) {
            entityManager.getTransaction().begin();
            entityManager.persist(new Record(
                    1L,
                    OffsetDateTime.parse("2023-05-01T00:00+02:00"),
                    OffsetDateTime.parse("2099-01-01T00:00+01:00"),
                    OffsetDateTime.parse("2023-05-01T00:00+02:00"),
                    OffsetDateTime.parse("2099-01-01T00:00+01:00")));
            entityManager.getTransaction().commit();
        }
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @DisplayName("The patched class is the one on the classpath")
    @Test
    void patched_class_is_active() throws Exception {
        var method = Class.forName("org.hibernate.query.internal.QueryParameterBindingImpl")
                .getDeclaredMethod("areTypesCompatible",
                        Class.forName("org.hibernate.metamodel.mapping.BasicValuedMapping"),
                        Class.forName("org.hibernate.metamodel.mapping.BasicValuedMapping"));

        assertThat(method).isNotNull();
        // Sanity check that we are running against the source copy, not the jar: the source copy lives in target/test-classes.
        var location = Class.forName("org.hibernate.query.internal.QueryParameterBindingImpl")
                .getProtectionDomain().getCodeSource().getLocation().toString();
        assertThat(location).as("must be loaded from test-classes, not from the hibernate-core jar").contains("test-classes");
    }

    @DisplayName("With the fix: 3 uses over 3 converted attributes binds and returns the row")
    @Test
    void three_uses_over_three_attributes_now_works() {
        assertThatNoException().isThrownBy(() -> query("""
                r.validFrom <= :now
                AND r.validTo > :now
                AND r.recordedFrom <= :now
                """));

        assertThat(query("r.validFrom <= :now AND r.validTo > :now AND r.recordedFrom <= :now")).hasSize(1);
    }

    @DisplayName("With the fix: all four validity attributes, one parameter")
    @Test
    void four_attributes_now_works() {
        assertThat(query("""
                r.validFrom <= :now
                AND r.validTo > :now
                AND r.recordedFrom <= :now
                AND r.recordedTo > :now
                """)).hasSize(1);
    }

    @DisplayName("With the fix: filtering still works, i.e. the value is bound correctly and not merely accepted")
    @Test
    void still_filters_correctly() {
        assertThat(queryAt("""
                r.validFrom <= :now
                AND r.validTo > :now
                AND r.recordedFrom <= :now
                AND r.recordedTo > :now
                """, OffsetDateTime.parse("2020-01-01T00:00+01:00")))
                .as("a point in time before the row's validity must not match")
                .isEmpty();
    }

    private java.util.List<Record> query(String whereClause) {
        return queryAt(whereClause, NOW);
    }

    private java.util.List<Record> queryAt(String whereClause, OffsetDateTime at) {
        try (var entityManager = factory.createEntityManager()) {
            return entityManager
                    .createQuery("SELECT r FROM Record r WHERE " + whereClause, Record.class)
                    .setParameter("now", at)
                    .getResultList();
        }
    }

}
