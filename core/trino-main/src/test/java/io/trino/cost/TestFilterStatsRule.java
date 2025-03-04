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

package io.trino.cost;

import io.trino.metadata.TestingFunctionResolution;
import io.trino.sql.ir.Comparison;
import io.trino.sql.ir.Constant;
import io.trino.sql.ir.Reference;
import io.trino.sql.planner.Symbol;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.sql.ir.Comparison.Operator.EQUAL;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
public class TestFilterStatsRule
        extends BaseStatsCalculatorTest
{
    public StatsCalculatorTester defaultFilterTester;

    @BeforeAll
    public void setupClass()
    {
        defaultFilterTester = new StatsCalculatorTester(
                testSessionBuilder()
                        .setSystemProperty("default_filter_factor_enabled", "true")
                        .build());
    }

    @AfterAll
    public void tearDownClass()
    {
        defaultFilterTester.close();
        defaultFilterTester = null;
    }

    @Test
    public void testEstimatableFilter()
    {
        tester().assertStatsFor(pb -> pb
                .filter(new Comparison(EQUAL, new Reference(BIGINT, "i1"), new Constant(BIGINT, 5L)),
                        pb.values(pb.symbol("i1", BIGINT), pb.symbol("i2", BIGINT), pb.symbol("i3", BIGINT))))
                .withSourceStats(0, PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10)
                        .addSymbolStatistics(new Symbol(BIGINT, "i1"), SymbolStatsEstimate.builder()
                                .setLowValue(1)
                                .setHighValue(10)
                                .setDistinctValuesCount(5)
                                .setNullsFraction(0)
                                .build())
                        .addSymbolStatistics(new Symbol(BIGINT, "i2"), SymbolStatsEstimate.builder()
                                .setLowValue(0)
                                .setHighValue(3)
                                .setDistinctValuesCount(4)
                                .setNullsFraction(0)
                                .build())
                        .addSymbolStatistics(new Symbol(BIGINT, "i3"), SymbolStatsEstimate.builder()
                                .setLowValue(10)
                                .setHighValue(15)
                                .setDistinctValuesCount(4)
                                .setNullsFraction(0.1)
                                .build())
                        .build())
                .check(check -> check
                        .outputRowsCount(2)
                        .symbolStats("i1", BIGINT, assertion -> assertion
                                .lowValue(5)
                                .highValue(5)
                                .distinctValuesCount(1)
                                .dataSizeUnknown()
                                .nullsFraction(0))
                        .symbolStats("i2", BIGINT, assertion -> assertion
                                .lowValue(0)
                                .highValue(3)
                                .dataSizeUnknown()
                                .distinctValuesCount(2)
                                .nullsFraction(0))
                        .symbolStats("i3", BIGINT, assertion -> assertion
                                .lowValue(10)
                                .highValue(15)
                                .dataSizeUnknown()
                                .distinctValuesCount(1.9)
                                .nullsFraction(0.05)));

        defaultFilterTester.assertStatsFor(pb -> pb
                .filter(new Comparison(EQUAL, new Reference(INTEGER, "i1"), new Constant(INTEGER, 5L)),
                        pb.values(pb.symbol("i1", INTEGER), pb.symbol("i2", INTEGER), pb.symbol("i3", INTEGER))))
                .withSourceStats(0, PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10)
                        .addSymbolStatistics(new Symbol(INTEGER, "i1"), SymbolStatsEstimate.builder()
                                .setLowValue(1)
                                .setHighValue(10)
                                .setDistinctValuesCount(5)
                                .setNullsFraction(0)
                                .build())
                        .addSymbolStatistics(new Symbol(INTEGER, "i2"), SymbolStatsEstimate.builder()
                                .setLowValue(0)
                                .setHighValue(3)
                                .setDistinctValuesCount(4)
                                .setNullsFraction(0)
                                .build())
                        .addSymbolStatistics(new Symbol(INTEGER, "i3"), SymbolStatsEstimate.builder()
                                .setLowValue(10)
                                .setHighValue(15)
                                .setDistinctValuesCount(4)
                                .setNullsFraction(0.1)
                                .build())
                        .build())
                .check(check -> check
                        .outputRowsCount(2)
                        .symbolStats("i1", INTEGER, assertion -> assertion
                                .lowValue(5)
                                .highValue(5)
                                .distinctValuesCount(1)
                                .dataSizeUnknown()
                                .nullsFraction(0))
                        .symbolStats("i2", INTEGER, assertion -> assertion
                                .lowValue(0)
                                .highValue(3)
                                .dataSizeUnknown()
                                .distinctValuesCount(2)
                                .nullsFraction(0))
                        .symbolStats("i3", INTEGER, assertion -> assertion
                                .lowValue(10)
                                .highValue(15)
                                .dataSizeUnknown()
                                .distinctValuesCount(1.9)
                                .nullsFraction(0.05)));
    }

    @Test
    public void testUnestimatableFunction()
    {
        // can't estimate function and default filter factor is turned off
        Comparison unestimatableExpression = new Comparison(
                EQUAL,
                new TestingFunctionResolution()
                        .functionCallBuilder("sin")
                        .addArgument(DOUBLE, new Reference(DOUBLE, "i1"))
                        .build(),
                new Constant(DOUBLE, 1.0));

        tester()
                .assertStatsFor(pb -> pb
                        .filter(unestimatableExpression,
                                pb.values(pb.symbol("i1", DOUBLE), pb.symbol("i2", DOUBLE), pb.symbol("i3", DOUBLE))))
                .withSourceStats(0, PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10)
                        .addSymbolStatistics(new Symbol(DOUBLE, "i1"), SymbolStatsEstimate.builder()
                                .setLowValue(1)
                                .setHighValue(10)
                                .setDistinctValuesCount(5)
                                .setNullsFraction(0)
                                .build())
                        .addSymbolStatistics(new Symbol(DOUBLE, "i2"), SymbolStatsEstimate.builder()
                                .setLowValue(0)
                                .setHighValue(3)
                                .setDistinctValuesCount(4)
                                .setNullsFraction(0)
                                .build())
                        .addSymbolStatistics(new Symbol(DOUBLE, "i3"), SymbolStatsEstimate.builder()
                                .setLowValue(10)
                                .setHighValue(15)
                                .setDistinctValuesCount(4)
                                .setNullsFraction(0.1)
                                .build())
                        .build())
                .check(PlanNodeStatsAssertion::outputRowsCountUnknown);

        // can't estimate function, but default filter factor is turned on
        defaultFilterTester.assertStatsFor(pb -> pb
                .filter(unestimatableExpression,
                        pb.values(pb.symbol("i1", DOUBLE), pb.symbol("i2", DOUBLE), pb.symbol("i3", DOUBLE))))
                .withSourceStats(0, PlanNodeStatsEstimate.builder()
                        .setOutputRowCount(10)
                        .addSymbolStatistics(new Symbol(DOUBLE, "i1"), SymbolStatsEstimate.builder()
                                .setLowValue(1)
                                .setHighValue(10)
                                .setDistinctValuesCount(5)
                                .setNullsFraction(0)
                                .build())
                        .addSymbolStatistics(new Symbol(DOUBLE, "i2"), SymbolStatsEstimate.builder()
                                .setLowValue(0)
                                .setHighValue(3)
                                .setDistinctValuesCount(4)
                                .setNullsFraction(0)
                                .build())
                        .addSymbolStatistics(new Symbol(DOUBLE, "i3"), SymbolStatsEstimate.builder()
                                .setLowValue(10)
                                .setHighValue(15)
                                .setDistinctValuesCount(4)
                                .setNullsFraction(0.1)
                                .build())
                        .build())
                .check(check -> check
                        .outputRowsCount(9)
                        .symbolStats("i1", assertion -> assertion
                                .lowValue(1)
                                .highValue(10)
                                .dataSizeUnknown()
                                .distinctValuesCount(5)
                                .nullsFraction(0))
                        .symbolStats("i2", assertion -> assertion
                                .lowValue(0)
                                .highValue(3)
                                .dataSizeUnknown()
                                .distinctValuesCount(4)
                                .nullsFraction(0))
                        .symbolStats("i3", assertion -> assertion
                                .lowValue(10)
                                .highValue(15)
                                .dataSizeUnknown()
                                .distinctValuesCount(4)
                                .nullsFraction(0.1)));
    }
}
