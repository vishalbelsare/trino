/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.trino.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import io.trino.metadata.ResolvedFunction;
import io.trino.metadata.TestingFunctionResolution;
import io.trino.spi.function.OperatorType;
import io.trino.sql.ir.Call;
import io.trino.sql.ir.Comparison;
import io.trino.sql.ir.Constant;
import io.trino.sql.ir.Expression;
import io.trino.sql.planner.Plan;
import io.trino.sql.planner.PlanNodeIdAllocator;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.assertions.PlanAssert;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.planner.iterative.rule.ReorderJoins.MultiJoinNode;
import io.trino.sql.planner.iterative.rule.test.PlanBuilder;
import io.trino.sql.planner.plan.Assignments;
import io.trino.sql.planner.plan.JoinNode;
import io.trino.sql.planner.plan.JoinNode.EquiJoinClause;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.planner.plan.ProjectNode;
import io.trino.sql.planner.plan.ValuesNode;
import io.trino.testing.QueryRunner;
import io.trino.testing.StandaloneQueryRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static io.airlift.testing.Closeables.closeAllRuntimeException;
import static io.trino.cost.PlanNodeStatsEstimate.unknown;
import static io.trino.cost.StatsAndCosts.empty;
import static io.trino.metadata.AbstractMockMetadata.dummyMetadata;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.sql.ir.Comparison.Operator.EQUAL;
import static io.trino.sql.ir.Comparison.Operator.GREATER_THAN;
import static io.trino.sql.ir.Comparison.Operator.LESS_THAN;
import static io.trino.sql.ir.Comparison.Operator.NOT_EQUAL;
import static io.trino.sql.ir.IrUtils.and;
import static io.trino.sql.planner.assertions.PlanMatchPattern.node;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.iterative.Lookup.noLookup;
import static io.trino.sql.planner.iterative.rule.ReorderJoins.MultiJoinNode.toMultiJoinNode;
import static io.trino.sql.planner.plan.JoinType.FULL;
import static io.trino.sql.planner.plan.JoinType.INNER;
import static io.trino.sql.planner.plan.JoinType.LEFT;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT;

@TestInstance(PER_CLASS)
@Execution(CONCURRENT)
public class TestJoinNodeFlattener
{
    private static final TestingFunctionResolution FUNCTIONS = new TestingFunctionResolution();
    private static final ResolvedFunction ADD_BIGINT = FUNCTIONS.resolveOperator(OperatorType.ADD, ImmutableList.of(BIGINT, BIGINT));
    private static final ResolvedFunction SUBTRACT_BIGINT = FUNCTIONS.resolveOperator(OperatorType.SUBTRACT, ImmutableList.of(BIGINT, BIGINT));
    private static final ResolvedFunction NEGATION_BIGINT = FUNCTIONS.resolveOperator(OperatorType.NEGATION, ImmutableList.of(BIGINT));

    private static final int DEFAULT_JOIN_LIMIT = 10;

    private QueryRunner queryRunner;

    @BeforeAll
    public void setUp()
    {
        queryRunner = new StandaloneQueryRunner(testSessionBuilder().build());
    }

    @AfterAll
    public void tearDown()
    {
        closeAllRuntimeException(queryRunner);
        queryRunner = null;
    }

    @Test
    public void testDoesNotAllowOuterJoin()
    {
        PlanNodeIdAllocator planNodeIdAllocator = new PlanNodeIdAllocator();
        PlanBuilder p = planBuilder(planNodeIdAllocator);
        Symbol a1 = p.symbol("A1");
        Symbol b1 = p.symbol("B1");
        JoinNode outerJoin = p.join(
                FULL,
                p.values(a1),
                p.values(b1),
                ImmutableList.of(equiJoinClause(a1, b1)),
                ImmutableList.of(a1),
                ImmutableList.of(b1),
                Optional.empty());
        assertThatThrownBy(() -> toMultiJoinNode(outerJoin, noLookup(), planNodeIdAllocator, DEFAULT_JOIN_LIMIT, false, testSessionBuilder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageMatching("join type must be.*");
    }

    @Test
    public void testDoesNotConvertNestedOuterJoins()
    {
        PlanNodeIdAllocator planNodeIdAllocator = new PlanNodeIdAllocator();
        PlanBuilder p = planBuilder(planNodeIdAllocator);
        Symbol a1 = p.symbol("A1");
        Symbol b1 = p.symbol("B1");
        Symbol c1 = p.symbol("C1");
        JoinNode leftJoin = p.join(
                LEFT,
                p.values(a1),
                p.values(b1),
                ImmutableList.of(equiJoinClause(a1, b1)),
                ImmutableList.of(a1),
                ImmutableList.of(b1),
                Optional.empty());
        ValuesNode valuesC = p.values(c1);
        JoinNode joinNode = p.join(
                INNER,
                leftJoin,
                valuesC,
                ImmutableList.of(equiJoinClause(a1, c1)),
                ImmutableList.of(a1, b1),
                ImmutableList.of(c1),
                Optional.empty());

        MultiJoinNode expected = MultiJoinNode.builder()
                .setSources(leftJoin, valuesC).setFilter(createEqualsExpression(a1, c1))
                .setOutputSymbols(a1, b1, c1)
                .build();
        assertThat(toMultiJoinNode(joinNode, noLookup(), planNodeIdAllocator, DEFAULT_JOIN_LIMIT, false, testSessionBuilder().build())).isEqualTo(expected);
    }

    @Test
    public void testPushesProjectionsThroughJoin()
    {
        PlanNodeIdAllocator planNodeIdAllocator = new PlanNodeIdAllocator();
        PlanBuilder p = planBuilder(planNodeIdAllocator);
        Symbol a = p.symbol("A");
        Symbol b = p.symbol("B");
        Symbol c = p.symbol("C");
        Symbol d = p.symbol("D");
        ValuesNode valuesA = p.values(a);
        ValuesNode valuesB = p.values(b);
        ValuesNode valuesC = p.values(c);
        JoinNode joinNode = p.join(
                INNER,
                p.project(
                        Assignments.of(d, new Call(NEGATION_BIGINT, ImmutableList.of(a.toSymbolReference()))),
                        p.join(
                                INNER,
                                valuesA,
                                valuesB,
                                equiJoinClause(a, b))),
                valuesC,
                equiJoinClause(d, c));
        MultiJoinNode actual = toMultiJoinNode(joinNode, noLookup(), planNodeIdAllocator, DEFAULT_JOIN_LIMIT, true, testSessionBuilder().build());
        assertThat(actual.getOutputSymbols()).isEqualTo(ImmutableList.of(d, c));
        assertThat(actual.getFilter()).isEqualTo(and(createEqualsExpression(a, b), createEqualsExpression(d, c)));
        assertThat(actual.isPushedProjectionThroughJoin()).isTrue();

        List<PlanNode> actualSources = ImmutableList.copyOf(actual.getSources());
        assertPlan(
                actualSources.get(0),
                node(
                        ProjectNode.class,
                        values("a"))
                        .withNumberOfOutputColumns(2));
        assertPlan(
                actualSources.get(1),
                node(
                        ProjectNode.class,
                        values("b"))
                        .withNumberOfOutputColumns(1));
        assertPlan(actualSources.get(2), values("c"));
    }

    @Test
    public void testDoesNotPushStraddlingProjection()
    {
        PlanNodeIdAllocator planNodeIdAllocator = new PlanNodeIdAllocator();
        PlanBuilder p = planBuilder(planNodeIdAllocator);
        Symbol a = p.symbol("A");
        Symbol b = p.symbol("B");
        Symbol c = p.symbol("C");
        Symbol d = p.symbol("D");
        ValuesNode valuesA = p.values(a);
        ValuesNode valuesB = p.values(b);
        ValuesNode valuesC = p.values(c);
        JoinNode joinNode = p.join(
                INNER,
                p.project(
                        Assignments.of(d, new Call(SUBTRACT_BIGINT, ImmutableList.of(a.toSymbolReference(), b.toSymbolReference()))),
                        p.join(
                                INNER,
                                valuesA,
                                valuesB,
                                equiJoinClause(a, b))),
                valuesC,
                equiJoinClause(d, c));
        MultiJoinNode actual = toMultiJoinNode(joinNode, noLookup(), planNodeIdAllocator, DEFAULT_JOIN_LIMIT, true, testSessionBuilder().build());
        assertThat(actual.getOutputSymbols()).isEqualTo(ImmutableList.of(d, c));
        assertThat(actual.getFilter()).isEqualTo(createEqualsExpression(d, c));
        assertThat(actual.isPushedProjectionThroughJoin()).isFalse();

        List<PlanNode> actualSources = ImmutableList.copyOf(actual.getSources());
        assertPlan(
                actualSources.get(0),
                node(
                        ProjectNode.class,
                        node(JoinNode.class,
                                values("a"),
                                values("b")))
                        .withNumberOfOutputColumns(1));
        assertPlan(actualSources.get(1), values("c"));
    }

    @Test
    public void testRetainsOutputSymbols()
    {
        PlanNodeIdAllocator planNodeIdAllocator = new PlanNodeIdAllocator();
        PlanBuilder p = planBuilder(planNodeIdAllocator);
        Symbol a1 = p.symbol("A1");
        Symbol b1 = p.symbol("B1");
        Symbol b2 = p.symbol("B2");
        Symbol c1 = p.symbol("C1");
        Symbol c2 = p.symbol("C2");
        ValuesNode valuesA = p.values(a1);
        ValuesNode valuesB = p.values(b1, b2);
        ValuesNode valuesC = p.values(c1, c2);
        JoinNode joinNode = p.join(
                INNER,
                valuesA,
                p.join(
                        INNER,
                        valuesB,
                        valuesC,
                        ImmutableList.of(equiJoinClause(b1, c1)),
                        ImmutableList.of(b1, b2),
                        ImmutableList.of(c1, c2),
                        Optional.empty()),
                ImmutableList.of(equiJoinClause(a1, b1)),
                ImmutableList.of(a1),
                ImmutableList.of(b1),
                Optional.empty());
        MultiJoinNode expected = MultiJoinNode.builder()
                .setSources(valuesA, valuesB, valuesC)
                .setFilter(and(createEqualsExpression(b1, c1), createEqualsExpression(a1, b1)))
                .setOutputSymbols(a1, b1)
                .build();
        assertThat(toMultiJoinNode(joinNode, noLookup(), planNodeIdAllocator, DEFAULT_JOIN_LIMIT, false, testSessionBuilder().build())).isEqualTo(expected);
    }

    @Test
    public void testCombinesCriteriaAndFilters()
    {
        PlanNodeIdAllocator planNodeIdAllocator = new PlanNodeIdAllocator();
        PlanBuilder p = planBuilder(planNodeIdAllocator);
        Symbol a1 = p.symbol("A1");
        Symbol b1 = p.symbol("B1");
        Symbol b2 = p.symbol("B2");
        Symbol c1 = p.symbol("C1");
        Symbol c2 = p.symbol("C2");
        ValuesNode valuesA = p.values(a1);
        ValuesNode valuesB = p.values(b1, b2);
        ValuesNode valuesC = p.values(c1, c2);
        Expression bcFilter = and(
                new Comparison(GREATER_THAN, c2.toSymbolReference(), new Constant(BIGINT, 0L)),
                new Comparison(NOT_EQUAL, c2.toSymbolReference(), new Constant(BIGINT, 7L)),
                new Comparison(GREATER_THAN, b2.toSymbolReference(), c2.toSymbolReference()));
        Comparison abcFilter = new Comparison(
                LESS_THAN,
                new Call(ADD_BIGINT, ImmutableList.of(a1.toSymbolReference(), c1.toSymbolReference())),
                b1.toSymbolReference());
        JoinNode joinNode = p.join(
                INNER,
                valuesA,
                p.join(
                        INNER,
                        valuesB,
                        valuesC,
                        ImmutableList.of(equiJoinClause(b1, c1)),
                        ImmutableList.of(b1, b2),
                        ImmutableList.of(c1, c2),
                        Optional.of(bcFilter)),
                ImmutableList.of(equiJoinClause(a1, b1)),
                ImmutableList.of(a1),
                ImmutableList.of(b1, b2, c1, c2),
                Optional.of(abcFilter));
        MultiJoinNode expected = new MultiJoinNode(
                new LinkedHashSet<>(ImmutableList.of(valuesA, valuesB, valuesC)),
                and(new Comparison(EQUAL, b1.toSymbolReference(), c1.toSymbolReference()), new Comparison(EQUAL, a1.toSymbolReference(), b1.toSymbolReference()), bcFilter, abcFilter),
                ImmutableList.of(a1, b1, b2, c1, c2),
                false);
        assertThat(toMultiJoinNode(joinNode, noLookup(), planNodeIdAllocator, DEFAULT_JOIN_LIMIT, false, testSessionBuilder().build())).isEqualTo(expected);
    }

    @Test
    public void testConvertsBushyTrees()
    {
        PlanNodeIdAllocator planNodeIdAllocator = new PlanNodeIdAllocator();
        PlanBuilder p = planBuilder(planNodeIdAllocator);
        Symbol a1 = p.symbol("A1");
        Symbol b1 = p.symbol("B1");
        Symbol c1 = p.symbol("C1");
        Symbol d1 = p.symbol("D1");
        Symbol d2 = p.symbol("D2");
        Symbol e1 = p.symbol("E1");
        Symbol e2 = p.symbol("E2");
        ValuesNode valuesA = p.values(a1);
        ValuesNode valuesB = p.values(b1);
        ValuesNode valuesC = p.values(c1);
        ValuesNode valuesD = p.values(d1, d2);
        ValuesNode valuesE = p.values(e1, e2);
        JoinNode joinNode = p.join(
                INNER,
                p.join(
                        INNER,
                        p.join(
                                INNER,
                                valuesA,
                                valuesB,
                                ImmutableList.of(equiJoinClause(a1, b1)),
                                ImmutableList.of(a1),
                                ImmutableList.of(b1),
                                Optional.empty()),
                        valuesC,
                        ImmutableList.of(equiJoinClause(a1, c1)),
                        ImmutableList.of(a1, b1),
                        ImmutableList.of(c1),
                        Optional.empty()),
                p.join(
                        INNER,
                        valuesD,
                        valuesE,
                        ImmutableList.of(
                                equiJoinClause(d1, e1),
                                equiJoinClause(d2, e2)),
                        ImmutableList.of(d1, d2),
                        ImmutableList.of(e1, e2),
                        Optional.empty()),
                ImmutableList.of(equiJoinClause(b1, e1)),
                ImmutableList.of(a1, b1, c1),
                ImmutableList.of(d1, d2, e1, e2),
                Optional.empty());
        MultiJoinNode expected = MultiJoinNode.builder()
                .setSources(valuesA, valuesB, valuesC, valuesD, valuesE)
                .setFilter(and(createEqualsExpression(a1, b1), createEqualsExpression(a1, c1), createEqualsExpression(d1, e1), createEqualsExpression(d2, e2), createEqualsExpression(b1, e1)))
                .setOutputSymbols(a1, b1, c1, d1, d2, e1, e2)
                .build();
        assertThat(toMultiJoinNode(joinNode, noLookup(), planNodeIdAllocator, 5, false, testSessionBuilder().build())).isEqualTo(expected);
    }

    @Test
    public void testMoreThanJoinLimit()
    {
        PlanNodeIdAllocator planNodeIdAllocator = new PlanNodeIdAllocator();
        PlanBuilder p = planBuilder(planNodeIdAllocator);
        Symbol a1 = p.symbol("A1");
        Symbol b1 = p.symbol("B1");
        Symbol c1 = p.symbol("C1");
        Symbol d1 = p.symbol("D1");
        Symbol d2 = p.symbol("D2");
        Symbol e1 = p.symbol("E1");
        Symbol e2 = p.symbol("E2");
        ValuesNode valuesA = p.values(a1);
        ValuesNode valuesB = p.values(b1);
        ValuesNode valuesC = p.values(c1);
        ValuesNode valuesD = p.values(d1, d2);
        ValuesNode valuesE = p.values(e1, e2);
        JoinNode join1 = p.join(
                INNER,
                valuesA,
                valuesB,
                ImmutableList.of(equiJoinClause(a1, b1)),
                ImmutableList.of(a1),
                ImmutableList.of(b1),
                Optional.empty());
        JoinNode join2 = p.join(
                INNER,
                valuesD,
                valuesE,
                ImmutableList.of(
                        equiJoinClause(d1, e1),
                        equiJoinClause(d2, e2)),
                ImmutableList.of(d1, d2),
                ImmutableList.of(e1, e2),
                Optional.empty());
        JoinNode joinNode = p.join(
                INNER,
                p.join(
                        INNER,
                        join1,
                        valuesC,
                        ImmutableList.of(equiJoinClause(a1, c1)),
                        ImmutableList.of(a1, b1),
                        ImmutableList.of(c1),
                        Optional.empty()),
                join2,
                ImmutableList.of(equiJoinClause(b1, e1)),
                ImmutableList.of(a1, b1, c1),
                ImmutableList.of(d1, d2, e1, e2),
                Optional.empty());
        MultiJoinNode expected = MultiJoinNode.builder()
                .setSources(join1, join2, valuesC)
                .setFilter(and(createEqualsExpression(a1, c1), createEqualsExpression(b1, e1)))
                .setOutputSymbols(a1, b1, c1, d1, d2, e1, e2)
                .build();
        assertThat(toMultiJoinNode(joinNode, noLookup(), planNodeIdAllocator, 2, false, testSessionBuilder().build())).isEqualTo(expected);
    }

    private Comparison createEqualsExpression(Symbol left, Symbol right)
    {
        return new Comparison(EQUAL, left.toSymbolReference(), right.toSymbolReference());
    }

    private EquiJoinClause equiJoinClause(Symbol symbol1, Symbol symbol2)
    {
        return new EquiJoinClause(symbol1, symbol2);
    }

    private PlanBuilder planBuilder(PlanNodeIdAllocator planNodeIdAllocator)
    {
        return new PlanBuilder(planNodeIdAllocator, queryRunner.getPlannerContext(), queryRunner.getDefaultSession());
    }

    private void assertPlan(PlanNode actual, PlanMatchPattern pattern)
    {
        PlanAssert.assertPlan(
                testSessionBuilder().build(),
                dummyMetadata(),
                queryRunner.getPlannerContext().getFunctionManager(),
                node -> unknown(),
                new Plan(actual, empty()), noLookup(),
                pattern);
    }
}
