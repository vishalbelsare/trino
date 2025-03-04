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
package io.trino.execution;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.Session;
import io.trino.execution.querystats.PlanOptimizersStatsCollector;
import io.trino.execution.warnings.DefaultWarningCollector;
import io.trino.execution.warnings.WarningCollector;
import io.trino.execution.warnings.WarningCollectorConfig;
import io.trino.matching.Captures;
import io.trino.matching.Pattern;
import io.trino.plugin.tpch.TpchConnectorFactory;
import io.trino.spi.TrinoException;
import io.trino.spi.TrinoWarning;
import io.trino.spi.WarningCode;
import io.trino.sql.planner.RuleStatsRecorder;
import io.trino.sql.planner.iterative.IterativeOptimizer;
import io.trino.sql.planner.iterative.Rule;
import io.trino.sql.planner.optimizations.PlanOptimizer;
import io.trino.sql.planner.plan.ProjectNode;
import io.trino.testing.PlanTester;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.trino.sql.planner.LogicalPlanner.Stage.OPTIMIZED;
import static io.trino.sql.planner.plan.Patterns.project;
import static io.trino.testing.TestingHandles.TEST_CATALOG_NAME;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static java.util.Objects.requireNonNull;
import static java.util.stream.IntStream.range;
import static org.assertj.core.api.Fail.fail;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT;

@TestInstance(PER_CLASS)
@Execution(CONCURRENT)
public class TestPlannerWarnings
{
    private PlanTester planTester;

    @BeforeAll
    public void setUp()
    {
        planTester = PlanTester.create(testSessionBuilder()
                .setCatalog(TEST_CATALOG_NAME)
                .setSchema("tiny")
                .build());

        planTester.createCatalog(
                planTester.getDefaultSession().getCatalog().get(),
                new TpchConnectorFactory(1),
                ImmutableMap.of());
    }

    @AfterAll
    public void tearDown()
    {
        planTester.close();
        planTester = null;
    }

    @Test
    public void testWarning()
    {
        List<TrinoWarning> warnings = createTestWarnings(3);
        List<WarningCode> warningCodes = warnings.stream()
                .map(TrinoWarning::getWarningCode)
                .collect(toImmutableList());
        assertPlannerWarnings(planTester, "SELECT * FROM NATION", ImmutableMap.of(), warningCodes, Optional.of(ImmutableList.of(new TestWarningsRule(warnings))));
    }

    public static void assertPlannerWarnings(PlanTester planTester, @Language("SQL") String sql, Map<String, String> sessionProperties, List<WarningCode> expectedWarnings, Optional<List<Rule<?>>> rules)
    {
        Session.SessionBuilder sessionBuilder = testSessionBuilder()
                .setCatalog(planTester.getDefaultSession().getCatalog())
                .setSchema(planTester.getDefaultSession().getSchema());
        sessionProperties.forEach(sessionBuilder::setSystemProperty);
        WarningCollector warningCollector = new DefaultWarningCollector(new WarningCollectorConfig());
        PlanOptimizersStatsCollector planOptimizersStatsCollector = new PlanOptimizersStatsCollector(5);
        try {
            planTester.inTransaction(sessionBuilder.build(), transactionSession -> {
                List<PlanOptimizer> planOptimizers;
                if (rules.isPresent()) {
                    // Warnings from testing rules will be added
                    planOptimizers = ImmutableList.of(new IterativeOptimizer(
                            planTester.getPlannerContext(),
                            new RuleStatsRecorder(),
                            planTester.getStatsCalculator(),
                            planTester.getCostCalculator(),
                            ImmutableSet.copyOf(rules.get())));
                }
                else {
                    planOptimizers = planTester.getPlanOptimizers(false);
                }
                planTester.createPlan(transactionSession, sql, planOptimizers, OPTIMIZED, warningCollector, planOptimizersStatsCollector);
                return null;
            });
        }
        catch (TrinoException e) {
            // ignore
        }
        Set<WarningCode> warnings = warningCollector.getWarnings().stream()
                .map(TrinoWarning::getWarningCode)
                .collect(toImmutableSet());
        for (WarningCode expectedWarning : expectedWarnings) {
            if (!warnings.contains(expectedWarning)) {
                fail("Expected warning: " + expectedWarning);
            }
        }
    }

    public static List<TrinoWarning> createTestWarnings(int numberOfWarnings)
    {
        checkArgument(numberOfWarnings > 0, "numberOfWarnings must be > 0");
        ImmutableList.Builder<TrinoWarning> builder = ImmutableList.builder();
        range(1, numberOfWarnings)
                .mapToObj(code -> new TrinoWarning(new WarningCode(code, "testWarning"), "Test warning " + code))
                .forEach(builder::add);
        return builder.build();
    }

    public static class TestWarningsRule
            implements Rule<ProjectNode>
    {
        private final List<TrinoWarning> warnings;

        public TestWarningsRule(List<TrinoWarning> warnings)
        {
            this.warnings = ImmutableList.copyOf(requireNonNull(warnings, "warnings is null"));
        }

        @Override
        public Pattern<ProjectNode> getPattern()
        {
            return project();
        }

        @Override
        public Result apply(ProjectNode node, Captures captures, Context context)
        {
            warnings.forEach(context.getWarningCollector()::add);
            return Result.empty();
        }
    }
}
