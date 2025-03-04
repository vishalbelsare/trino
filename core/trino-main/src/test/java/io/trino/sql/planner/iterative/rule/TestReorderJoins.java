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
import com.google.common.collect.ImmutableMap;
import io.trino.cost.CostComparator;
import io.trino.cost.PlanNodeStatsEstimate;
import io.trino.cost.SymbolStatsEstimate;
import io.trino.metadata.ResolvedFunction;
import io.trino.metadata.TestingFunctionResolution;
import io.trino.spi.function.OperatorType;
import io.trino.spi.type.Type;
import io.trino.sql.ir.Call;
import io.trino.sql.ir.Comparison;
import io.trino.sql.ir.Reference;
import io.trino.sql.planner.OptimizerConfig.JoinDistributionType;
import io.trino.sql.planner.OptimizerConfig.JoinReorderingStrategy;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.assertions.PlanMatchPattern;
import io.trino.sql.planner.iterative.rule.test.RuleBuilder;
import io.trino.sql.planner.iterative.rule.test.RuleTester;
import io.trino.sql.planner.plan.Assignments;
import io.trino.sql.planner.plan.JoinNode.EquiJoinClause;
import io.trino.sql.planner.plan.PlanNodeId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;

import java.util.Optional;

import static io.airlift.testing.Closeables.closeAllRuntimeException;
import static io.trino.SystemSessionProperties.JOIN_DISTRIBUTION_TYPE;
import static io.trino.SystemSessionProperties.JOIN_MAX_BROADCAST_TABLE_SIZE;
import static io.trino.SystemSessionProperties.JOIN_REORDERING_STRATEGY;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static io.trino.sql.ir.Comparison.Operator.EQUAL;
import static io.trino.sql.ir.Comparison.Operator.LESS_THAN;
import static io.trino.sql.planner.OptimizerConfig.JoinDistributionType.AUTOMATIC;
import static io.trino.sql.planner.OptimizerConfig.JoinDistributionType.BROADCAST;
import static io.trino.sql.planner.assertions.PlanMatchPattern.expression;
import static io.trino.sql.planner.assertions.PlanMatchPattern.join;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.strictProject;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.plan.JoinNode.DistributionType.PARTITIONED;
import static io.trino.sql.planner.plan.JoinNode.DistributionType.REPLICATED;
import static io.trino.sql.planner.plan.JoinType.INNER;
import static io.trino.type.UnknownType.UNKNOWN;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT;

@TestInstance(PER_CLASS)
@Execution(CONCURRENT)
public class TestReorderJoins
{
    private static final TestingFunctionResolution FUNCTIONS = new TestingFunctionResolution();
    private static final ResolvedFunction NEGATION_BIGINT = FUNCTIONS.resolveOperator(OperatorType.NEGATION, ImmutableList.of(BIGINT));

    private RuleTester tester;

    @BeforeAll
    public void setUp()
    {
        tester = RuleTester.builder()
                .addSessionProperty(JOIN_DISTRIBUTION_TYPE, JoinDistributionType.AUTOMATIC.name())
                .addSessionProperty(JOIN_REORDERING_STRATEGY, JoinReorderingStrategy.AUTOMATIC.name())
                .withNodeCountForStats(4)
                .build();
    }

    @AfterAll
    public void tearDown()
    {
        closeAllRuntimeException(tester);
        tester = null;
    }

    @Test
    public void testKeepsOutputSymbols()
    {
        assertReorderJoins()
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(5000)
                        .addSymbolStatistics(ImmutableMap.of(
                                new Symbol(BIGINT, "A1"), new SymbolStatsEstimate(0, 100, 0, 100, 100),
                                new Symbol(BIGINT, "A2"), new SymbolStatsEstimate(0, 100, 0, 100, 100)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "B1"), new SymbolStatsEstimate(0, 100, 0, 100, 100)))
                        .build())
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), 2, p.symbol("A1", BIGINT), p.symbol("A2", BIGINT)),
                                p.values(new PlanNodeId("valuesB"), 2, p.symbol("B1", BIGINT)),
                                ImmutableList.of(new EquiJoinClause(p.symbol("A1", BIGINT), p.symbol("B1", BIGINT))),
                                ImmutableList.of(p.symbol("A2", BIGINT)),
                                ImmutableList.of(),
                                Optional.empty()))
                .matches(
                        project(
                                join(INNER, builder -> builder
                                        .equiCriteria("A1", "B1")
                                        .distributionType(PARTITIONED)
                                        .left(values(ImmutableMap.of("A1", 0, "A2", 1)))
                                        .right(values(ImmutableMap.of("B1", 0)))))
                                .withExactOutputs("A2"));
    }

    @Test
    public void testReplicatesAndFlipsWhenOneTableMuchSmaller()
    {
        Type symbolType = createUnboundedVarcharType(); // variable width so that average row size is respected
        assertReorderJoins()
                .setSystemProperty(JOIN_MAX_BROADCAST_TABLE_SIZE, "1PB")
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(100)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(symbolType, "A1"), new SymbolStatsEstimate(0, 100, 0, 6400, 100)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(symbolType, "B1"), new SymbolStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .on(p -> {
                    Symbol a1 = p.symbol("A1", symbolType);
                    Symbol b1 = p.symbol("B1", symbolType);
                    return p.join(
                            INNER,
                            p.values(new PlanNodeId("valuesA"), 2, a1),
                            p.values(new PlanNodeId("valuesB"), 2, b1),
                            ImmutableList.of(new EquiJoinClause(a1, b1)),
                            ImmutableList.of(a1),
                            ImmutableList.of(b1),
                            Optional.empty());
                })
                .matches(
                        project(
                                join(INNER, builder -> builder
                                        .equiCriteria("B1", "A1")
                                        .distributionType(REPLICATED)
                                        .left(values(ImmutableMap.of("B1", 0)))
                                        .right(values(ImmutableMap.of("A1", 0))))));
    }

    @Test
    public void testRepartitionsWhenRequiredBySession()
    {
        Type symbolType = createUnboundedVarcharType(); // variable width so that average row size is respected
        assertReorderJoins()
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, JoinDistributionType.PARTITIONED.name())
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(100)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(symbolType, "A1"), new SymbolStatsEstimate(0, 100, 0, 6400, 100)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(symbolType, "B1"), new SymbolStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .on(p -> {
                    Symbol a1 = p.symbol("A1", symbolType);
                    Symbol b1 = p.symbol("B1", symbolType);
                    return p.join(
                            INNER,
                            p.values(new PlanNodeId("valuesA"), 2, a1),
                            p.values(new PlanNodeId("valuesB"), 2, b1),
                            ImmutableList.of(new EquiJoinClause(a1, b1)),
                            ImmutableList.of(a1),
                            ImmutableList.of(b1),
                            Optional.empty());
                })
                .matches(
                        project(
                                join(INNER, builder -> builder
                                        .equiCriteria("B1", "A1")
                                        .distributionType(PARTITIONED)
                                        .left(values(ImmutableMap.of("B1", 0)))
                                        .right(values(ImmutableMap.of("A1", 0))))));
    }

    @Test
    public void testRepartitionsWhenBothTablesEqual()
    {
        assertReorderJoins()
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "A1"), new SymbolStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "B1"), new SymbolStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), 2, p.symbol("A1", BIGINT)),
                                p.values(new PlanNodeId("valuesB"), 2, p.symbol("B1", BIGINT)),
                                ImmutableList.of(new EquiJoinClause(p.symbol("A1", BIGINT), p.symbol("B1", BIGINT))),
                                ImmutableList.of(p.symbol("A1", BIGINT)),
                                ImmutableList.of(p.symbol("B1", BIGINT)),
                                Optional.empty()))
                .matches(
                        project(
                                join(INNER, builder -> builder
                                        .equiCriteria("A1", "B1")
                                        .distributionType(PARTITIONED)
                                        .left(values(ImmutableMap.of("A1", 0)))
                                        .right(values(ImmutableMap.of("B1", 0))))));
    }

    @Test
    public void testReplicatesUnrestrictedWhenRequiredBySession()
    {
        assertReorderJoins()
                .setSystemProperty(JOIN_MAX_BROADCAST_TABLE_SIZE, "1kB")
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, BROADCAST.name())
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "A1"), new SymbolStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "B1"), new SymbolStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), 2, p.symbol("A1", BIGINT)),
                                p.values(new PlanNodeId("valuesB"), 2, p.symbol("B1", BIGINT)),
                                ImmutableList.of(new EquiJoinClause(p.symbol("A1", BIGINT), p.symbol("B1", BIGINT))),
                                ImmutableList.of(p.symbol("A1", BIGINT)),
                                ImmutableList.of(p.symbol("B1", BIGINT)),
                                Optional.empty()))
                .matches(
                        project(
                                join(INNER, builder -> builder
                                        .equiCriteria("A1", "B1")
                                        .distributionType(REPLICATED)
                                        .left(values(ImmutableMap.of("A1", 0)))
                                        .right(values(ImmutableMap.of("B1", 0))))));
    }

    @Test
    public void testReplicatedScalarJoinEvenWhereSessionRequiresRepartitioned()
    {
        PlanMatchPattern expectedPlan = project(
                join(INNER, builder -> builder
                        .equiCriteria("A1", "B1")
                        .distributionType(REPLICATED)
                        .left(values(ImmutableMap.of("A1", 0)))
                        .right(values(ImmutableMap.of("B1", 0)))));

        PlanNodeStatsEstimate valuesA = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(10000)
                .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "A1"), new SymbolStatsEstimate(0, 100, 0, 640000, 100)))
                .build();
        PlanNodeStatsEstimate valuesB = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(10000)
                .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "B1"), new SymbolStatsEstimate(0, 100, 0, 640000, 100)))
                .build();

        assertReorderJoins()
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, JoinDistributionType.PARTITIONED.name())
                .overrideStats("valuesA", valuesA)
                .overrideStats("valuesB", valuesB)
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), p.symbol("A1", BIGINT)), // matches isAtMostScalar
                                p.values(new PlanNodeId("valuesB"), 2, p.symbol("B1", BIGINT)),
                                ImmutableList.of(new EquiJoinClause(p.symbol("A1", BIGINT), p.symbol("B1", BIGINT))),
                                ImmutableList.of(p.symbol("A1", BIGINT)),
                                ImmutableList.of(p.symbol("B1", BIGINT)),
                                Optional.empty()))
                .matches(expectedPlan);

        assertReorderJoins()
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, JoinDistributionType.PARTITIONED.name())
                .overrideStats("valuesA", valuesA)
                .overrideStats("valuesB", valuesB)
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesB"), 2, p.symbol("B1", BIGINT)),
                                p.values(new PlanNodeId("valuesA"), p.symbol("A1", BIGINT)), // matches isAtMostScalar
                                ImmutableList.of(new EquiJoinClause(p.symbol("B1", BIGINT), p.symbol("A1", BIGINT))),
                                ImmutableList.of(p.symbol("B1", BIGINT)),
                                ImmutableList.of(p.symbol("A1", BIGINT)),
                                Optional.empty()))
                .matches(expectedPlan);
    }

    @Test
    public void testDoesNotFireForCrossJoin()
    {
        assertReorderJoins()
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(UNKNOWN, "A1"), new SymbolStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10000)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(UNKNOWN, "B1"), new SymbolStatsEstimate(0, 100, 0, 640000, 100)))
                        .build())
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), 2, p.symbol("A1")),
                                p.values(new PlanNodeId("valuesB"), 2, p.symbol("B1")),
                                ImmutableList.of(),
                                ImmutableList.of(p.symbol("A1")),
                                ImmutableList.of(p.symbol("B1")),
                                Optional.empty()))
                .doesNotFire();
    }

    @Test
    public void testDoesNotFireWithNoStats()
    {
        assertReorderJoins()
                .overrideStats("valuesA", PlanNodeStatsEstimate.unknown())
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), 2, p.symbol("A1")),
                                p.values(new PlanNodeId("valuesB"), p.symbol("B1")),
                                ImmutableList.of(new EquiJoinClause(p.symbol("A1"), p.symbol("B1"))),
                                ImmutableList.of(p.symbol("A1")),
                                ImmutableList.of(),
                                Optional.empty()))
                .doesNotFire();
    }

    @Test
    public void testDoesNotFireForNonDeterministicFilter()
    {
        assertReorderJoins()
                .on(p ->
                        p.join(
                                INNER,
                                p.values(new PlanNodeId("valuesA"), p.symbol("A1", DOUBLE)),
                                p.values(new PlanNodeId("valuesB"), p.symbol("B1", DOUBLE)),
                                ImmutableList.of(new EquiJoinClause(p.symbol("A1", DOUBLE), p.symbol("B1", DOUBLE))),
                                ImmutableList.of(p.symbol("A1", DOUBLE)),
                                ImmutableList.of(p.symbol("B1", DOUBLE)),
                                Optional.of(new Comparison(
                                        LESS_THAN,
                                        p.symbol("A1", DOUBLE).toSymbolReference(),
                                        new TestingFunctionResolution().functionCallBuilder("random").build()))))
                .doesNotFire();
    }

    @Test
    public void testPredicatesPushedDown()
    {
        assertReorderJoins()
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "A1"), new SymbolStatsEstimate(0, 100, 0, 100, 10)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(5)
                        .addSymbolStatistics(ImmutableMap.of(
                                new Symbol(BIGINT, "B1"), new SymbolStatsEstimate(0, 100, 0, 100, 5),
                                new Symbol(BIGINT, "B2"), new SymbolStatsEstimate(0, 100, 0, 100, 5)))
                        .build())
                .overrideStats("valuesC", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(1000)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "C1"), new SymbolStatsEstimate(0, 100, 0, 100, 100)))
                        .build())
                .on(p ->
                        p.join(
                                INNER,
                                p.join(
                                        INNER,
                                        p.values(new PlanNodeId("valuesA"), 2, p.symbol("A1", BIGINT)),
                                        p.values(new PlanNodeId("valuesB"), 2, p.symbol("B1", BIGINT), p.symbol("B2", BIGINT)),
                                        ImmutableList.of(),
                                        ImmutableList.of(p.symbol("A1", BIGINT)),
                                        ImmutableList.of(p.symbol("B1", BIGINT), p.symbol("B2", BIGINT)),
                                        Optional.empty()),
                                p.values(new PlanNodeId("valuesC"), 2, p.symbol("C1", BIGINT)),
                                ImmutableList.of(
                                        new EquiJoinClause(p.symbol("B2", BIGINT), p.symbol("C1", BIGINT))),
                                ImmutableList.of(p.symbol("A1", BIGINT)),
                                ImmutableList.of(),
                                Optional.of(new Comparison(EQUAL, p.symbol("A1", BIGINT).toSymbolReference(), p.symbol("B1", BIGINT).toSymbolReference()))))
                .matches(
                        project(
                                join(INNER, builder -> builder
                                        .equiCriteria("C1", "B2")
                                        .left(values("C1"))
                                        .right(
                                                join(INNER, rightJoinBuilder -> rightJoinBuilder
                                                        .equiCriteria("A1", "B1")
                                                        .left(values("A1"))
                                                        .right(values("B1", "B2")))))));
    }

    @Test
    public void testPushesProjectionsThroughJoin()
    {
        assertReorderJoins()
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "A1"), new SymbolStatsEstimate(0, 100, 0, 100, 10)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(5)
                        .addSymbolStatistics(ImmutableMap.of(
                                new Symbol(BIGINT, "B1"), new SymbolStatsEstimate(0, 100, 0, 100, 5)))
                        .build())
                .overrideStats("valuesC", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(1000)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "C1"), new SymbolStatsEstimate(0, 100, 0, 100, 100)))
                        .build())
                .on(p ->
                        p.join(
                                INNER,
                                p.project(
                                        Assignments.of(
                                                p.symbol("P1", BIGINT), new Call(NEGATION_BIGINT, ImmutableList.of(p.symbol("B1", BIGINT).toSymbolReference())),
                                                p.symbol("P2", BIGINT), p.symbol("A1", BIGINT).toSymbolReference()),
                                        p.join(
                                                INNER,
                                                p.values(new PlanNodeId("valuesA"), 2, p.symbol("A1", BIGINT)),
                                                p.values(new PlanNodeId("valuesB"), 2, p.symbol("B1", BIGINT)),
                                                ImmutableList.of(),
                                                ImmutableList.of(p.symbol("A1", BIGINT)),
                                                ImmutableList.of(p.symbol("B1", BIGINT)),
                                                Optional.empty())),
                                p.values(new PlanNodeId("valuesC"), 2, p.symbol("C1", BIGINT)),
                                ImmutableList.of(new EquiJoinClause(p.symbol("P1", BIGINT), p.symbol("C1", BIGINT))),
                                ImmutableList.of(p.symbol("P1", BIGINT)),
                                ImmutableList.of(),
                                Optional.of(new Comparison(EQUAL, p.symbol("P2", BIGINT).toSymbolReference(), p.symbol("C1", BIGINT).toSymbolReference()))))
                .matches(
                        project(
                                join(INNER, builder -> builder
                                        .equiCriteria("C1", "P1")
                                        .left(values("C1"))
                                        .right(
                                                join(INNER, rightJoinBuilder -> rightJoinBuilder
                                                        .equiCriteria("P2", "P1")
                                                        .left(
                                                                strictProject(
                                                                        ImmutableMap.of("P2", expression(new Reference(BIGINT, "A1"))),
                                                                        values("A1")))
                                                        .right(
                                                                strictProject(
                                                                        ImmutableMap.of("P1", expression(new Call(NEGATION_BIGINT, ImmutableList.of(new Reference(BIGINT, "B1"))))),
                                                                        values("B1"))))))));
    }

    @Test
    public void testDoesNotPushProjectionThroughJoinIfTooExpensive()
    {
        assertReorderJoins()
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "A1"), new SymbolStatsEstimate(0, 100, 0, 100, 10)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(5)
                        .addSymbolStatistics(ImmutableMap.of(
                                new Symbol(BIGINT, "B1"), new SymbolStatsEstimate(0, 100, 0, 100, 5)))
                        .build())
                .overrideStats("valuesC", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(1000)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "C1"), new SymbolStatsEstimate(0, 100, 0, 100, 100)))
                        .build())
                .on(p ->
                        p.join(
                                INNER,
                                p.project(
                                        Assignments.of(
                                                p.symbol("P1", BIGINT), new Call(NEGATION_BIGINT, ImmutableList.of(p.symbol("B1", BIGINT).toSymbolReference()))),
                                        p.join(
                                                INNER,
                                                p.values(new PlanNodeId("valuesA"), 2, p.symbol("A1", BIGINT)),
                                                p.values(new PlanNodeId("valuesB"), 2, p.symbol("B1", BIGINT)),
                                                ImmutableList.of(new EquiJoinClause(p.symbol("A1", BIGINT), p.symbol("B1", BIGINT))),
                                                ImmutableList.of(p.symbol("A1", BIGINT)),
                                                ImmutableList.of(p.symbol("B1", BIGINT)),
                                                Optional.empty())),
                                p.values(new PlanNodeId("valuesC"), 2, p.symbol("C1", BIGINT)),
                                ImmutableList.of(new EquiJoinClause(p.symbol("P1", BIGINT), p.symbol("C1", BIGINT))),
                                ImmutableList.of(p.symbol("P1", BIGINT)),
                                ImmutableList.of(),
                                Optional.empty()))
                .matches(
                        project(
                                join(INNER, builder -> builder
                                        .equiCriteria("C1", "P1")
                                        .left(values("C1"))
                                        .right(
                                                strictProject(
                                                        ImmutableMap.of("P1", expression(new Call(NEGATION_BIGINT, ImmutableList.of(new Reference(BIGINT, "B1"))))),
                                                        join(INNER, rightJoinBuilder -> rightJoinBuilder
                                                                .equiCriteria("A1", "B1")
                                                                .left(values("A1"))
                                                                .right(values("B1"))))))));
    }

    @Test
    public void testSmallerJoinFirst()
    {
        assertReorderJoins()
                .overrideStats("valuesA", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(40)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "A1"), new SymbolStatsEstimate(0, 100, 0, 100, 10)))
                        .build())
                .overrideStats("valuesB", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10)
                        .addSymbolStatistics(ImmutableMap.of(
                                new Symbol(BIGINT, "B1"), new SymbolStatsEstimate(0, 100, 0, 100, 10),
                                new Symbol(BIGINT, "B2"), new SymbolStatsEstimate(0, 100, 0, 100, 10)))
                        .build())
                .overrideStats("valuesC", PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(100)
                        .addSymbolStatistics(ImmutableMap.of(new Symbol(BIGINT, "C1"), new SymbolStatsEstimate(99, 199, 0, 100, 100)))
                        .build())
                .on(p ->
                        p.join(
                                INNER,
                                p.join(
                                        INNER,
                                        p.values(new PlanNodeId("valuesA"), 2, p.symbol("A1", BIGINT)),
                                        p.values(new PlanNodeId("valuesB"), 2, p.symbol("B1", BIGINT), p.symbol("B2", BIGINT)),
                                        ImmutableList.of(new EquiJoinClause(p.symbol("A1", BIGINT), p.symbol("B1", BIGINT))),
                                        ImmutableList.of(p.symbol("A1", BIGINT)),
                                        ImmutableList.of(p.symbol("B1", BIGINT), p.symbol("B2", BIGINT)),
                                        Optional.empty()),
                                p.values(new PlanNodeId("valuesC"), 2, p.symbol("C1", BIGINT)),
                                ImmutableList.of(
                                        new EquiJoinClause(p.symbol("B2", BIGINT), p.symbol("C1", BIGINT))),
                                ImmutableList.of(p.symbol("A1", BIGINT)),
                                ImmutableList.of(),
                                Optional.of(new Comparison(EQUAL, p.symbol("A1", BIGINT).toSymbolReference(), p.symbol("B1", BIGINT).toSymbolReference()))))
                .matches(
                        project(join(INNER, builder -> builder
                                .equiCriteria("A1", "B1")
                                .left(values("A1"))
                                .right(
                                        join(INNER, rightJoinBuilder -> rightJoinBuilder
                                                .equiCriteria("C1", "B2")
                                                .left(values("C1"))
                                                .right(values("B1", "B2")))))));
    }

    @Test
    public void testReplicatesWhenNotRestricted()
    {
        Type symbolType = createUnboundedVarcharType(); // variable width so that average row size is respected
        int aRows = 10_000;
        int bRows = 10;

        PlanNodeStatsEstimate probeSideStatsEstimate = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(aRows)
                .addSymbolStatistics(ImmutableMap.of(new Symbol(symbolType, "A1"), new SymbolStatsEstimate(0, 100, 0, 640000, 10)))
                .build();
        PlanNodeStatsEstimate buildSideStatsEstimate = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(bRows)
                .addSymbolStatistics(ImmutableMap.of(new Symbol(symbolType, "B1"), new SymbolStatsEstimate(0, 100, 0, 640000, 10)))
                .build();

        // B table is small enough to be replicated according to JOIN_MAX_BROADCAST_TABLE_SIZE limit
        assertReorderJoins()
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, AUTOMATIC.name())
                .setSystemProperty(JOIN_MAX_BROADCAST_TABLE_SIZE, "100MB")
                .overrideStats("valuesA", probeSideStatsEstimate)
                .overrideStats("valuesB", buildSideStatsEstimate)
                .on(p -> {
                    Symbol a1 = p.symbol("A1", symbolType);
                    Symbol b1 = p.symbol("B1", symbolType);
                    return p.join(
                            INNER,
                            p.values(new PlanNodeId("valuesA"), aRows, a1),
                            p.values(new PlanNodeId("valuesB"), bRows, b1),
                            ImmutableList.of(new EquiJoinClause(a1, b1)),
                            ImmutableList.of(a1),
                            ImmutableList.of(b1),
                            Optional.empty());
                })
                .matches(
                        project(
                                join(INNER, builder -> builder
                                        .equiCriteria("A1", "B1")
                                        .distributionType(REPLICATED)
                                        .left(values(ImmutableMap.of("A1", 0)))
                                        .right(values(ImmutableMap.of("B1", 0))))));

        probeSideStatsEstimate = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(aRows)
                .addSymbolStatistics(ImmutableMap.of(new Symbol(symbolType, "A1"), new SymbolStatsEstimate(0, 100, 0, 640000d * 10000, 10)))
                .build();
        buildSideStatsEstimate = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(bRows)
                .addSymbolStatistics(ImmutableMap.of(new Symbol(symbolType, "B1"), new SymbolStatsEstimate(0, 100, 0, 640000d * 10000, 10)))
                .build();

        // B table exceeds JOIN_MAX_BROADCAST_TABLE_SIZE limit therefore it is partitioned
        assertReorderJoins()
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, AUTOMATIC.name())
                .setSystemProperty(JOIN_MAX_BROADCAST_TABLE_SIZE, "100MB")
                .overrideStats("valuesA", probeSideStatsEstimate)
                .overrideStats("valuesB", buildSideStatsEstimate)
                .on(p -> {
                    Symbol a1 = p.symbol("A1", symbolType);
                    Symbol b1 = p.symbol("B1", symbolType);
                    return p.join(
                            INNER,
                            p.values(new PlanNodeId("valuesA"), aRows, a1),
                            p.values(new PlanNodeId("valuesB"), bRows, b1),
                            ImmutableList.of(new EquiJoinClause(a1, b1)),
                            ImmutableList.of(a1),
                            ImmutableList.of(b1),
                            Optional.empty());
                })
                .matches(
                        project(
                                join(INNER, builder -> builder
                                        .equiCriteria("A1", "B1")
                                        .distributionType(PARTITIONED)
                                        .left(values(ImmutableMap.of("A1", 0)))
                                        .right(values(ImmutableMap.of("B1", 0))))));
    }

    @Test
    public void testReorderAndReplicate()
    {
        Type symbolType = createUnboundedVarcharType(); // variable width so that average row size is respected
        int aRows = 10;
        int bRows = 10_000;

        PlanNodeStatsEstimate probeSideStatsEstimate = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(aRows)
                .addSymbolStatistics(ImmutableMap.of(new Symbol(symbolType, "A1"), new SymbolStatsEstimate(0, 100, 0, 640000, 10)))
                .build();
        PlanNodeStatsEstimate buildSideStatsEstimate = PlanNodeStatsEstimate.builder()
                .setOutputRowCount(bRows)
                .addSymbolStatistics(ImmutableMap.of(new Symbol(symbolType, "B1"), new SymbolStatsEstimate(0, 100, 0, 640000, 10)))
                .build();

        // A table is small enough to be replicated in JOIN_MAX_BROADCAST_TABLE_SIZE mode
        assertReorderJoins()
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, AUTOMATIC.name())
                .setSystemProperty(JOIN_REORDERING_STRATEGY, AUTOMATIC.name())
                .setSystemProperty(JOIN_MAX_BROADCAST_TABLE_SIZE, "10MB")
                .overrideStats("valuesA", probeSideStatsEstimate)
                .overrideStats("valuesB", buildSideStatsEstimate)
                .on(p -> {
                    Symbol a1 = p.symbol("A1", symbolType);
                    Symbol b1 = p.symbol("B1", symbolType);
                    return p.join(
                            INNER,
                            p.values(new PlanNodeId("valuesA"), aRows, a1),
                            p.values(new PlanNodeId("valuesB"), bRows, b1),
                            ImmutableList.of(new EquiJoinClause(a1, b1)),
                            ImmutableList.of(a1),
                            ImmutableList.of(b1),
                            Optional.empty());
                })
                .matches(
                        project(
                                join(INNER, builder -> builder
                                        .equiCriteria("B1", "A1")
                                        .distributionType(REPLICATED)
                                        .left(values(ImmutableMap.of("B1", 0)))
                                        .right(values(ImmutableMap.of("A1", 0))))));
    }

    private RuleBuilder assertReorderJoins()
    {
        return tester.assertThat(new ReorderJoins(tester.getPlannerContext(), new CostComparator(1, 1, 1)));
    }
}
