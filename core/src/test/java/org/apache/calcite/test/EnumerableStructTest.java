/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.test;

import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.java.ReflectiveSchema;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableInterpretable;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaRecordType;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.prepare.CalciteSqlValidator;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlAbstractConformance;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.tools.RelBuilder;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression tests for direct enumerable compilation without JDBC struct flattening. */
public class EnumerableStructTest {
  private List<?> run(String sql) throws Exception {
    return run(sql, CalciteSchema.createRootSchema(false));
  }

  private List<?> run(String sql, CalciteSchema schema) throws Exception {
    JavaTypeFactoryImpl types = new JavaTypeFactoryImpl();
    CalciteCatalogReader catalog =
        new CalciteCatalogReader(
            schema, Collections.emptyList(), types, CalciteConnectionConfig.DEFAULT);
    SqlValidator validator =
        new CalciteSqlValidator(
            SqlStdOperatorTable.instance(), catalog, types,
            SqlValidator.Config.DEFAULT.withIdentifierExpansion(true));
    SqlNode node = validator.validate(SqlParser.create(sql).parseQuery());
    VolcanoPlanner planner = new VolcanoPlanner();
    planner.addRelTraitDef(ConventionTraitDef.INSTANCE);
    planner.addRelTraitDef(RelCollationTraitDef.INSTANCE);
    RelOptUtil.registerDefaultRules(planner, false, false);
    RelOptCluster cluster = RelOptCluster.create(planner, new RexBuilder(types));
    SqlToRelConverter converter =
        new SqlToRelConverter(
            null, validator, catalog, cluster, StandardConvertletTable.INSTANCE,
            SqlToRelConverter.config().withTrimUnusedFields(false)
                .withDecorrelationEnabled(false));
    RelRoot root = converter.convertQuery(node, false, true);
    return run(root.rel, schema);
  }

  private List<?> run(RelNode logical, CalciteSchema schema) throws Exception {
    JavaTypeFactoryImpl types = (JavaTypeFactoryImpl) logical.getCluster().getTypeFactory();
    RelNode rel =
        Programs.standard().run(
            logical.getCluster().getPlanner(), logical,
            logical.getTraitSet().replace(EnumerableConvention.INSTANCE),
            Collections.emptyList(), Collections.emptyList());
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("_conformance", new SqlAbstractConformance() {
      @Override public boolean isRowConstructorAlwaysPresent() {
        return true;
      }
    });
    DataContext context = new DataContext() {
      @Override public SchemaPlus getRootSchema() {
        return schema.plus();
      }

      @Override public JavaTypeFactoryImpl getTypeFactory() {
        return types;
      }

      @Override public QueryProvider getQueryProvider() {
        return Linq4j.DEFAULT_PROVIDER;
      }

      @Override public @Nullable Object get(String name) {
        return parameters.get(name);
      }
    };
    return EnumerableInterpretable.toBindable(parameters, null, (EnumerableRel) rel,
        EnumerableRel.Prefer.ARRAY).bind(context).toList();
  }

  /** Normalizes physical ROW/ARRAY representations for value assertions. */
  private static Object normalize(Object value) {
    if (value instanceof Object[]) {
      return Arrays.stream((Object[]) value)
          .map(EnumerableStructTest::normalize).collect(Collectors.toList());
    }
    if (value instanceof List) {
      return ((List<?>) value).stream()
          .map(EnumerableStructTest::normalize).collect(Collectors.toList());
    }
    return value;
  }

  @Test void testScalarCaseNegativeLiteral() throws Exception {
    List<?> rows = run("select case when x > 0 then 3 else -1 end as c, x "
        + "from (values (1), (-1)) as t(x)");
    assertEquals(2, rows.size());
    assertEquals(Arrays.asList(3, 1), normalize(rows.get(0)));
    assertEquals(Arrays.asList(-1, -1), normalize(rows.get(1)));
  }

  /** Derived from FP's location feature regressions added in commit 57df6480b. */
  @Test void testCaseWhenWithStructAndNull() throws Exception {
    List<?> rows = run("select case when status <> 'invalid' then "
        + "cast(row(cast(lat as double), cast(lng as double)) "
        + "as row(latitude double, longitude double)) "
        + "else null end as location "
        + "from (values ('valid', 1.0, 2.0), ('invalid', 3.0, 4.0)) t(status, lat, lng)");
    assertEquals(2, rows.size());
    assertEquals(Arrays.asList(1.0, 2.0), normalize(rows.get(0)));
    assertNull(rows.get(1));
  }

  @Test void testNullableRecordWithAllNullMembers() throws Exception {
    List<?> rows = run("select x, case when x = 1 then "
        + "cast(row(cast(null as varchar), cast(null as varchar)) "
        + "as row(holder_type varchar, holder_hash varchar)) else null end as holder "
        + "from (values (0), (1)) t(x)");
    assertEquals(2, rows.size());
    assertEquals(Arrays.asList(0, null), normalize(rows.get(0)));
    assertEquals(Arrays.asList(1, Arrays.asList(null, null)), normalize(rows.get(1)));
  }

  @Test void testRecordCaseInsideAnotherRecord() throws Exception {
    List<?> rows = run("select cast(row(case when x = 1 then "
        + "cast(row(cast(null as varchar), cast(null as varchar)) "
        + "as row(holder_type varchar, holder_hash varchar)) else null end) "
        + "as row(holder row(holder_type varchar, holder_hash varchar))) "
        + "from (values (0), (1)) t(x)");
    assertEquals(2, rows.size());
    assertEquals(Collections.singletonList(null), normalize(rows.get(0)));
    assertEquals(Collections.singletonList(Arrays.asList(null, null)), normalize(rows.get(1)));
  }

  @Test void testNestedAllNullRecordRetainsPresence() throws Exception {
    List<?> rows = run("select x, case when x = 1 then cast(row("
        + "cast(row(cast(null as varchar), cast(null as varchar)) "
        + "as row(holder_type varchar, holder_hash varchar))) "
        + "as row(holder row(holder_type varchar, holder_hash varchar))) "
        + "else null end from (values (0), (1)) t(x)");
    assertEquals(2, rows.size());
    assertEquals(Arrays.asList(0, null), normalize(rows.get(0)));
    assertEquals(Arrays.asList(1, Collections.singletonList(Arrays.asList(null, null))),
        normalize(rows.get(1)));
  }

  @Test void testRecordCaseKeepsUnselectedBranchLazy() throws Exception {
    List<?> rows = run("select case when x = 1 then "
        + "cast(row(cast(s as integer)) as row(number_value integer)) "
        + "else cast(row(7) as row(number_value integer)) end "
        + "from (values (0, 'not-an-integer'), (1, '5')) t(x, s)");
    assertEquals(Arrays.asList(Collections.singletonList(7), Collections.singletonList(5)),
        normalize(rows));
  }

  @Test void testNestedCasePreservesJavaBackedRecords() throws Exception {
    PojoValue selected = new PojoValue("selected");
    PojoValue fallback = new PojoValue("fallback");
    PojoValue allNull = new PojoValue(null);
    PojoSchema fixture = new PojoSchema(new PojoInput[] {
        new PojoInput(1, selected, fallback),
        new PojoInput(0, selected, fallback),
        new PojoInput(1, allNull, fallback),
        new PojoInput(1, null, fallback),
        new PojoInput(0, selected, null)
    });
    CalciteSchema schema = CalciteSchema.createRootSchema(false);
    schema.add("POJO", new ReflectiveSchema(fixture));
    RelBuilder builder = RelBuilder.create(
        Frameworks.newConfigBuilder().defaultSchema(schema.plus()).build());
    builder.scan("POJO", "inputs");
    // Exercise a caller-supplied native result type; SQL inference can erase that metadata.
    JavaTypeFactoryImpl types = (JavaTypeFactoryImpl) builder.getTypeFactory();
    RexNode conditional = builder.getRexBuilder().makeCall(types.createType(PojoValue.class),
        SqlStdOperatorTable.CASE,
        Arrays.asList(builder.equals(builder.field("choice"), builder.literal(1)),
            builder.field("value"), builder.field("alternative")));
    assertInstanceOf(JavaRecordType.class, conditional.getType());
    List<?> rows = run(builder.project(builder.call(SqlStdOperatorTable.ROW, conditional))
        .build(), schema);
    assertEquals(Arrays.asList(
        Collections.singletonList(selected),
        Collections.singletonList(fallback),
        Collections.singletonList(allNull),
        Collections.singletonList(null),
        Collections.singletonList(null)), normalize(rows));
  }

  /** A native Java record, deliberately not an enumerable Object[] row. */
  public static class PojoValue {
    public final @Nullable String label;

    PojoValue(@Nullable String label) {
      this.label = label;
    }
  }

  /** Input fields retain JavaRecordType through CASE inference. */
  public static class PojoInput {
    public final int choice;
    public final @Nullable PojoValue value;
    public final @Nullable PojoValue alternative;

    PojoInput(int choice, @Nullable PojoValue value, @Nullable PojoValue alternative) {
      this.choice = choice;
      this.value = value;
      this.alternative = alternative;
    }
  }

  /** Reflective input table for native-record execution. */
  public static class PojoSchema {
    public final PojoInput[] inputs;

    PojoSchema(PojoInput[] inputs) {
      this.inputs = inputs;
    }
  }

  /** Derived from FP's Wi-Fi transform regression added in commit 0ba0eb21e. */
  @Test void testStructContainingArrayOfStructs() throws Exception {
    List<?> rows = run("select cast(row(array("
        + "select cast(row(cast(bssid as bigint), level, connected) "
        + "as row(bssid bigint, level integer, connected boolean)) "
        + "from (values (123, -42, true)) t(bssid, level, connected)"
        + ")) as row(wifi_scan row(bssid bigint, level integer, connected boolean) array))");
    assertEquals(1, rows.size());
    assertEquals(
        Collections.singletonList(
            Collections.singletonList(Arrays.asList(123L, -42, true))),
        normalize(rows.get(0)));
  }
}
