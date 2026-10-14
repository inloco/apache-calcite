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

import org.apache.calcite.DataContexts;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableInterpretable;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
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
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;
import org.apache.calcite.tools.Programs;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression tests for direct enumerable compilation without JDBC struct flattening. */
class EnumerableStructTest {
  private List<?> run(String sql) throws Exception {
    JavaTypeFactoryImpl types = new JavaTypeFactoryImpl();
    CalciteSchema schema = CalciteSchema.createRootSchema(false);
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
    RelNode rel =
        Programs.standard().run(
            planner, root.rel, root.rel.getTraitSet().replace(EnumerableConvention.INSTANCE),
            Collections.emptyList(), Collections.emptyList());
    Map<String, Object> parameters = new HashMap<>();
    return EnumerableInterpretable.toBindable(parameters, null, (EnumerableRel) rel,
        EnumerableRel.Prefer.ARRAY).bind(DataContexts.of(parameters)).toList();
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
