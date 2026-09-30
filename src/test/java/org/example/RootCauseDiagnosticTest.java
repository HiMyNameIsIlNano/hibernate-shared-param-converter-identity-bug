package org.example;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.BasicValuedMapping;
import org.hibernate.query.spi.QueryImplementor;
import org.hibernate.type.NullType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins down <em>why</em> the query in {@link SharedParameterAcrossConvertedAttributesTest} fails, so the diagnosis does
 * not rest on reading the stack trace alone.
 * <p>
 * Two facts are asserted:
 * <ol>
 *   <li>the four attributes share one converter <em>class</em> and one {@code JdbcType}, but each has its own converter
 *       <em>instance</em> — which is what {@code QueryParameterBindingImpl.areTypesCompatible} compares with
 *       {@code ==};</li>
 *   <li>consequently the parameter binding of the failing query degrades to {@link NullType}, whereas the working
 *       2-use query keeps a real type.</li>
 * </ol>
 */
class RootCauseDiagnosticTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2023-05-03T00:00+02:00");

    private static EntityManagerFactory factory;

    @BeforeAll
    static void startUp() {
        factory = Persistence.createEntityManagerFactory("reproducer");
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @DisplayName("Each attribute gets its own converter instance, though class and JdbcType are shared")
    @Test
    void converter_instances_differ_per_attribute() {
        var mappingMetamodel = factory.unwrap(SessionFactoryImplementor.class).getMappingMetamodel();
        var descriptor = mappingMetamodel.getEntityDescriptor(Record.class);

        var attributes = new String[]{"validFrom", "validTo", "recordedFrom", "recordedTo"};
        var converters = new java.util.ArrayList<Object>();
        var jdbcTypes = new java.util.ArrayList<Object>();

        for (var attribute : attributes) {
            var mapping = (BasicValuedMapping) descriptor.findAttributeMapping(attribute);
            var jdbcMapping = mapping.getSingleJdbcMapping();
            converters.add(jdbcMapping.getValueConverter());
            jdbcTypes.add(jdbcMapping.getJdbcType());

            System.out.printf("%-14s jdbcType=%-12s converter=%s@%s%n",
                    attribute,
                    System.identityHashCode(jdbcMapping.getJdbcType()),
                    jdbcMapping.getValueConverter().getClass().getSimpleName(),
                    System.identityHashCode(jdbcMapping.getValueConverter()));
        }

        // Same JdbcType instance for all four ...
        assertThat(jdbcTypes).containsOnly(jdbcTypes.get(0));
        // ... and the same converter class ...
        assertThat(converters).allSatisfy(converter ->
                assertThat(converter).isInstanceOf(converters.get(0).getClass()));

        // ... but four DISTINCT converter INSTANCES. This is the crux: areTypesCompatible() compares them with '=='.
        for (int i = 1; i < converters.size(); i++) {
            assertThat(converters.get(i))
                    .as("converter instance of %s must not be identical to the one of %s", attributes[i], attributes[0])
                    .isNotSameAs(converters.get(0));
        }

        // And here is why the one-line fix works: AttributeConverterBean overrides equals() (since 7.3), and by that
        // definition all four converters ARE equal. Only the identity check makes them look incompatible.
        for (var converter : converters) {
            assertThat(converter)
                    .as("all four converters are equal(), just not identical")
                    .isEqualTo(converters.get(0));
        }
    }

    @DisplayName("The failing query degrades the parameter binding to NullType, the working one does not")
    @Test
    void binding_degrades_to_null_type() {
        assertThat(bindTypeOf("r.validFrom <= :now AND r.validTo > :now"))
                .as("2 uses / 2 attributes -> type is inferred")
                .isNotInstanceOf(NullType.class);

        assertThat(bindTypeOf("r.validFrom <= :now AND r.validTo > :now AND r.recordedFrom <= :now"))
                .as("3 uses / 3 attributes -> Hibernate gives up and falls back to NullType")
                .isInstanceOf(NullType.class);
    }

    /** Returns the resolved bind type of {@code :now} without executing the query. */
    private Object bindTypeOf(String whereClause) {
        try (var entityManager = factory.createEntityManager()) {
            var query = entityManager
                    .createQuery("SELECT r FROM Record r WHERE " + whereClause, Record.class)
                    .setParameter("now", NOW);

            try {
                query.getResultList();
            } catch (RuntimeException expected) {
                // The failing variant throws; the binding is inspected either way.
            }

            var implementor = query.unwrap(QueryImplementor.class);
            var parameter = implementor.getParameterMetadata().getQueryParameter("now");
            return implementor.unwrap(org.hibernate.query.sqm.internal.SqmQueryImpl.class)
                    .getQueryParameterBindings()
                    .getBinding(parameter)
                    .getBindType();
        }
    }

}
