package org.example;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reusing ONE named parameter at THREE or more places, spread over at least TWO different attributes that carry the
 * same {@code AttributeConverter}, fails to bind.
 *
 * <pre>
 * java.lang.IllegalArgumentException: Unsure how to handle given Java type [java.time.LocalDateTime]
 *                                     as TemporalType#TIMESTAMP
 * </pre>
 *
 * The same query with a distinct parameter per predicate works, and so does any number of repetitions of a single
 * attribute — see {@link #threshold()}.
 * <p>
 * This is a regression introduced in 7.4.0: on 7.3.2.Final every query below simply works, because
 * {@code QueryParameterBindingImpl} had neither {@code areTypesCompatible} nor the {@code NullType} fallback yet. Run
 * {@code mvn test -Dhibernate.version=7.3.2.Final} to see these assertions fail for the opposite reason, i.e. because
 * nothing is thrown any more.
 */
class SharedParameterAcrossConvertedAttributesTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2023-05-03T00:00+02:00");

    private static EntityManagerFactory factory;

    @BeforeAll
    static void startUp() {
        factory = Persistence.createEntityManagerFactory("reproducer");

        inTransaction(entityManager -> entityManager.persist(new Record(
                1L,
                OffsetDateTime.parse("2023-05-01T00:00+02:00"),
                OffsetDateTime.parse("2099-01-01T00:00+01:00"),
                OffsetDateTime.parse("2023-05-01T00:00+02:00"),
                OffsetDateTime.parse("2099-01-01T00:00+01:00"))));
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    private static void inTransaction(java.util.function.Consumer<EntityManager> work) {
        try (var entityManager = factory.createEntityManager()) {
            entityManager.getTransaction().begin();
            try {
                work.accept(entityManager);
                entityManager.getTransaction().commit();
            } catch (RuntimeException e) {
                entityManager.getTransaction().rollback();
                throw e;
            }
        }
    }

    private List<Record> query(String whereClause, Object... params) {
        try (var entityManager = factory.createEntityManager()) {
            var query = entityManager.createQuery("SELECT r FROM Record r WHERE " + whereClause, Record.class);
            for (int i = 0; i < params.length; i += 2) {
                query.setParameter((String) params[i], params[i + 1]);
            }
            return query.getResultList();
        }
    }

    @DisplayName("BUG: one parameter, three uses, three converted attributes")
    @Test
    void fails_with_three_uses_over_three_attributes() {
        assertThatThrownBy(() -> query("""
                r.validFrom <= :now
                AND r.validTo > :now
                AND r.recordedFrom <= :now
                """, "now", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsure how to handle given Java type [java.time.LocalDateTime] as TemporalType#TIMESTAMP");
    }

    @DisplayName("Works: the very same query with a distinct parameter per predicate")
    @Test
    void works_with_one_parameter_per_predicate() {
        assertThatNoException().isThrownBy(() -> query("""
                r.validFrom <= :a
                AND r.validTo > :b
                AND r.recordedFrom <= :c
                """, "a", NOW, "b", NOW, "c", NOW));

        assertThat(query("r.validFrom <= :a AND r.validTo > :b AND r.recordedFrom <= :c",
                "a", NOW, "b", NOW, "c", NOW)).hasSize(1);
    }

    @DisplayName("Works: one parameter used four times, but always on the SAME attribute")
    @Test
    void works_when_all_uses_hit_one_attribute() {
        assertThatNoException().isThrownBy(() -> query("""
                r.validFrom <= :now
                AND r.validFrom <= :now
                AND r.validFrom <= :now
                AND r.validFrom <= :now
                """, "now", NOW));
    }

    @DisplayName("The threshold is: >= 3 uses AND >= 2 distinct converted attributes")
    @Test
    void threshold() {
        // 1 use
        assertThatNoException().isThrownBy(() -> query("r.validFrom <= :now", "now", NOW));
        // 2 uses / 2 attributes -> still fine
        assertThatNoException().isThrownBy(() -> query("r.validFrom <= :now AND r.validTo > :now", "now", NOW));
        // 3 uses / 1 attribute -> fine, it is the same converter instance
        assertThatNoException().isThrownBy(() ->
                query("r.validFrom <= :now AND r.validFrom <= :now AND r.validFrom <= :now", "now", NOW));

        // 3 uses / 2 attributes -> breaks
        assertThatThrownBy(() -> query("r.validFrom <= :now AND r.validTo > :now AND r.validFrom <= :now", "now", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        // 4 uses / 4 attributes -> breaks
        assertThatThrownBy(() -> query("""
                r.validFrom <= :now AND r.validTo > :now AND r.recordedFrom <= :now AND r.recordedTo > :now
                """, "now", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
