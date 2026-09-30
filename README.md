# Reproducer: shared JPQL parameter over several attributes with the same `AttributeConverter`

Minimal, self-contained reproducer for a Hibernate ORM 7.4 regression. Plain Hibernate + H2, no Spring, no
Testcontainers, two entity classes.

```bash
mvn test                              # reproduces the bug (6 tests, all green: they ASSERT the failure)
mvn test -P with-fix                  # shows the proposed one-line fix working (4 tests)
mvn test -Dhibernate.version=7.3.2.Final   # pre-regression baseline: the queries just work
```

| File | Purpose |
| --- | --- |
| `SharedParameterAcrossConvertedAttributesTest` | Reproduces the failure and pins the exact threshold |
| `RootCauseDiagnosticTest` | Proves the root cause (converter instances, `NullType` fallback) |
| `src/fix/java/.../QueryParameterBindingImpl.java` | Verbatim copy of the 7.4.5 class with one line changed |
| `src/fix/java/.../ProposedFixTest` | Asserts the patched class makes the failing queries work |

---

## Issue to report

### `Unsure how to handle given Java type [java.time.LocalDateTime] as TemporalType#TIMESTAMP` when one parameter is reused across several attributes sharing an `AttributeConverter`

**Affected versions:** 7.4.0.Final – 7.4.11.Final (latest tested). Works on 7.3.2.Final, so this is a 7.4 regression.

#### Description

A JPQL query that compares several entity attributes against **one single named parameter** fails to bind when

* the attributes are mapped with the same `@Convert(converter = ...)`, whose relational type is not one of the types
  `BindingTypeHelper.resolveTimestampTemporalTypeVariant` knows, and
* the parameter is used **three or more times** across **at least two different** such attributes.

```java
entityManager.createQuery("""
        SELECT r FROM Record r
        WHERE r.validFrom <= :now
          AND r.validTo > :now
          AND r.recordedFrom <= :now
        """, Record.class)
    .setParameter("now", OffsetDateTime.now())
    .getResultList();
```

```
java.lang.IllegalArgumentException: Unsure how to handle given Java type [java.time.LocalDateTime]
                                    as TemporalType#TIMESTAMP
    at org.hibernate.type.internal.BindingTypeHelper.resolveTimestampTemporalTypeVariant(BindingTypeHelper.java:144)
    at org.hibernate.type.internal.BindingTypeHelper.resolveBindType(BindingTypeHelper.java:110)
    at org.hibernate.sql.exec.spi.JdbcParameterBindings.createAndAddBinding(JdbcParameterBindings.java:97)
    at org.hibernate.sql.exec.internal.AbstractJdbcParameter.forEachDisassembledJdbcValue(AbstractJdbcParameter.java:184)
    at org.hibernate.metamodel.mapping.Bindable.forEachJdbcValue(Bindable.java:173)
    at org.hibernate.sql.exec.spi.JdbcParameterBindings.registerParametersForEachJdbcValue(JdbcParameterBindings.java:77)
    at org.hibernate.query.sqm.internal.SqmUtil.createValueBindings(SqmUtil.java:724)
    at org.hibernate.query.sqm.internal.SqmUtil.createJdbcParameterBinding(SqmUtil.java:657)
    at org.hibernate.query.sqm.internal.SqmUtil.createJdbcParameterBindings(SqmUtil.java:532)
    ...
```

The mapping itself is entirely ordinary — an `AttributeConverter<OffsetDateTime, LocalDateTime>` storing a wall clock
in a column without offset:

```java
@Converter
public class UtcOffsetConverter implements AttributeConverter<OffsetDateTime, LocalDateTime> { ... }

@Entity
public class Record {
    @Convert(converter = UtcOffsetConverter.class) private OffsetDateTime validFrom;
    @Convert(converter = UtcOffsetConverter.class) private OffsetDateTime validTo;
    @Convert(converter = UtcOffsetConverter.class) private OffsetDateTime recordedFrom;
    @Convert(converter = UtcOffsetConverter.class) private OffsetDateTime recordedTo;
}
```

#### What exactly triggers it

The threshold is oddly specific, which is what makes this look like a bug rather than a limitation:

| Query | Result |
| --- | --- |
| `validFrom <= :now` | works |
| `validFrom <= :now AND validTo > :now` | works |
| `validFrom <= :now AND validTo > :now AND recordedFrom <= :now` | **fails** |
| `validFrom <= :now AND validTo > :now AND validFrom <= :now` | **fails** |
| `validFrom <= :now` repeated 4× (same attribute) | works |
| `validFrom <= :a AND validTo > :b AND recordedFrom <= :c` | works |

So: **≥ 3 uses AND ≥ 2 distinct attributes.** One attribute repeated any number of times is fine, and splitting the
one parameter into several distinct ones is fine.

#### Root cause as far as I can tell

`QueryParameterBindingImpl.setType` (7.4.5.Final, line 289) re-checks on each further use of the parameter whether the
new attribute is compatible with the previous one, and bails out to `NullType` when it is not:

```java
if ( changed && previousType instanceof BasicValuedMapping previousBasicValuedMapping
    && !areTypesCompatible( basicValuedMapping, previousBasicValuedMapping ) ) {
    // An SQM parameter is used in multiple type-incompatible contexts,
    // so let's play safe and not force a type onto the binding,
    // but let SQM type inference dictate the type instead
    this.type = NullType.INSTANCE;
    this.bindType = (BindableType<T>) NullType.INSTANCE;
    return true;
}
```

The compatibility check (line 322) compares the two value converters by **reference identity**:

```java
private boolean areTypesCompatible(BasicValuedMapping mapping1, BasicValuedMapping mapping2) {
    final JdbcMapping jdbcMapping1 = mapping1.getSingleJdbcMapping();
    final JdbcMapping jdbcMapping2 = mapping2.getSingleJdbcMapping();
    return jdbcMapping1.getJdbcType() == jdbcMapping2.getJdbcType()
        && jdbcMapping1.getValueConverter() == jdbcMapping2.getValueConverter();   // <-- here
}
```

But Hibernate instantiates **one `AttributeConverterBean` per attribute**, so four attributes carrying the very same
converter class end up with four distinct converter instances. `RootCauseDiagnosticTest` asserts this:

```
validFrom      jdbcType=809729692    converter=AttributeConverterBean@772109879
validTo        jdbcType=809729692    converter=AttributeConverterBean@1982828534
recordedFrom   jdbcType=809729692    converter=AttributeConverterBean@820849190
recordedTo     jdbcType=809729692    converter=AttributeConverterBean@468780345
```

Identical `JdbcType` instance, identical converter class — four different converter instances. Two structurally
identical attributes are therefore judged *incompatible*, and Hibernate drops the inferred type.

Once the binding is `NullType`, `JdbcParameterBindings.createAndAddBinding` types the value by its **runtime class**
instead. By that point the `AttributeConverter` has already converted `OffsetDateTime` → `LocalDateTime`, and
`BindingTypeHelper.resolveTimestampTemporalTypeVariant` handles `Calendar`, `java.util.Date`, `Instant`,
`OffsetDateTime`, `ZonedDateTime` and `OffsetTime` — but not `LocalDateTime` — so it throws.

That also explains the threshold: the bail-out only triggers once `previousType` is already a `BasicValuedMapping`, so
the comparison first bites on the third use. And repeating one attribute is harmless because it is literally the same
converter instance, so `==` holds.

#### Suggested fix

**Method:** `org.hibernate.query.internal.QueryParameterBindingImpl#areTypesCompatible`

Compare the converters by equality instead of by identity:

```diff
 private boolean areTypesCompatible(BasicValuedMapping mapping1, BasicValuedMapping mapping2) {
     final JdbcMapping jdbcMapping1 = mapping1.getSingleJdbcMapping();
     final JdbcMapping jdbcMapping2 = mapping2.getSingleJdbcMapping();
     return jdbcMapping1.getJdbcType() == jdbcMapping2.getJdbcType()
-           && jdbcMapping1.getValueConverter() == jdbcMapping2.getValueConverter();
+           && Objects.equals( jdbcMapping1.getValueConverter(), jdbcMapping2.getValueConverter() );
 }
```

This works out of the box because `AttributeConverterBean` already overrides `equals`/`hashCode` (since 7.3, over
`attributeConverterBean`, `converterJavaType`, `domainJavaType` and `jdbcJavaType`), so two converter beans for the
same converter class and the same domain/relational types compare equal. `RootCauseDiagnosticTest` asserts both
halves: the instances are *not* identical, but they *are* `equals`.

I verified this end-to-end rather than only by inspection — `mvn test -P with-fix` puts a verbatim copy of
`QueryParameterBindingImpl` from 7.4.5.Final with exactly that one line changed on the test classpath, where it shadows
the class from the jar. All previously failing queries then bind, return the expected row, and still filter correctly
(a point in time outside the row's validity yields no result).

Caveat: `Objects.equals` makes the check *less* strict, so it relies on converter `equals` implementations being
sound. If that is not a safe assumption for arbitrary `BasicValueConverter` implementations, a narrower alternative
would be to compare converter class plus domain and relational `JavaType`, or to make the `NullType` bail-out
conditional on the relational java type actually being unsupported by `BindingTypeHelper`.

#### Questions

1. Is the reference comparison in `areTypesCompatible` intentional, or should it be an equality comparison?
2. Should `AttributeConverterBean` instances be shared/interned across attributes that declare the identical converter
   in the first place? That would fix this as a side effect and save a few objects per entity.
3. Independently of the above: should `resolveTimestampTemporalTypeVariant` handle `LocalDateTime`? Falling through to
   an `IllegalArgumentException` for a `java.time` type that Hibernate otherwise maps natively is surprising, and the
   message gives no hint that the real problem is a parameter reused across converted attributes.

#### Environment

* Hibernate ORM 7.4.0.Final through 7.4.11.Final (reproduced on 7.4.0, 7.4.1, 7.4.5, 7.4.11); **not** reproducible on
  7.3.2.Final
* H2 2.4.240, JDK 17 (Temurin 17.0.16), Maven, macOS
* `TZ=Europe/Berlin` (set by the build; only relevant so the converter's zone arithmetic is deterministic)
