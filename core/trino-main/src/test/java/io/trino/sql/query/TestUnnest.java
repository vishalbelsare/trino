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
package io.trino.sql.query;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT;

@TestInstance(PER_CLASS)
@Execution(CONCURRENT)
public class TestUnnest
{
    private final QueryAssertions assertions = new QueryAssertions();

    @AfterAll
    public void teardown()
    {
        assertions.close();
    }

    @Test
    public void testUnnestArrayRows()
    {
        assertThat(assertions.query(
                "SELECT * FROM UNNEST(ARRAY[ROW(1, 1.1), ROW(3, 3.3)], ARRAY[ROW('a', true), ROW('b', false)])"))
                .matches("VALUES (1, 1.1, 'a', true), (3, 3.3, 'b', false)");
        assertThat(assertions.query(
                "SELECT * FROM UNNEST(ARRAY[ROW(1, 1.1), ROW(3, 3.3)], ARRAY[ROW('a', true), null])"))
                .matches("VALUES (1, 1.1, 'a', true), (3, 3.3, null, null)");
        assertThat(assertions.query(
                "SELECT * FROM UNNEST(ARRAY[ROW(1, 1.1), ROW(3, 3.3)], ARRAY[null, ROW('a', true), null])"))
                .matches("VALUES (1, 1.1, null, null), (3, 3.3,  'a', true), (null, null, null, null)");
        assertThat(assertions.query(
                "SELECT * FROM UNNEST(ARRAY[ROW(1, 1.1), ROW(3, 3.3)], ARRAY[null, ROW(null, true), null])"))
                .matches("VALUES (1, 1.1, null, null), (3, 3.3,  null, true), (null, null, null, null)");

        assertThat(assertions.query(
                "SELECT x, y FROM (VALUES (ARRAY[ROW(1.0, 2), ROW(3, 4.123)])) AS t(a) CROSS JOIN UNNEST(a) t(x, y)"))
                .matches("VALUES (1.0, 2), (3, 4.123)");
        assertThat(assertions.query(
                "SELECT x, y, z FROM (VALUES (ARRAY[ROW(1, 2), ROW(3, 4)])) t(a) CROSS JOIN (VALUES (1), (2)) s(z) CROSS JOIN UNNEST(a) t(x, y)"))
                .matches("VALUES (1, 2, 1), (1, 2, 2), (3, 4, 1), (3, 4, 2)");
    }

    @Test
    public void testUnnestPreserveColumnName()
    {
        assertThat(assertions.query(
                "SELECT x FROM UNNEST(CAST(ARRAY[ROW(1, 'a'), ROW(2, 'b')] as ARRAY(ROW(x int, y varchar))))"))
                .matches("VALUES (1), (2)");

        assertThat(assertions.query(
                "SELECT x FROM" +
                        "(VALUES (3)) AS t(x)" +
                        "CROSS JOIN UNNEST(CAST(ARRAY[ROW(1, 'a'), ROW(2, 'b')] as ARRAY(ROW(x int, y varchar))))"))
                .failure().hasMessageMatching(".*Column 'x' is ambiguous.*");

        assertThat(assertions.query(
                "SELECT t.x FROM" +
                        "(VALUES (3)) AS t(x)" +
                        "CROSS JOIN UNNEST(CAST(ARRAY[ROW(1, 'a'), ROW(2, 'b')] as ARRAY(ROW(x int, y varchar))))"))
                .matches("VALUES (3), (3)");

        assertThat(assertions.query(
                "SELECT u.x FROM" +
                        "(VALUES (3)) AS t(x)" +
                        "CROSS JOIN UNNEST(CAST(ARRAY[ROW(1, 'a'), ROW(2, 'b')] as ARRAY(ROW(x int, y varchar)))) u"))
                .matches("VALUES (1), (2)");
    }

    @Test
    public void testUnnestMultiExpr()
    {
        assertThat(assertions.query(
                "SELECT x " +
                        "FROM UNNEST(" +
                        "   CAST(ARRAY[ROW(1, 'a'), ROW(2, 'b')] as ARRAY(ROW(x int, y varchar)))," +
                        "   CAST(ARRAY[ROW(1, 'a'), ROW(2, 'b')] as ARRAY(ROW(x int, y varchar))))"))
                .failure().hasMessageMatching(".*Column 'x' is ambiguous.*");

        assertThat(assertions.query(
                "SELECT t3 " +
                        "FROM UNNEST(" +
                        "   CAST(ARRAY[ROW(1, 'a'), ROW(2, 'b')] as ARRAY(ROW(x int, y varchar)))," +
                        "   CAST(ARRAY[ROW(3, 'c'), ROW(4, 'd')] as ARRAY(ROW(x int, y varchar)))) t(t1,t2,t3,t4)"))
                .matches("VALUES (3), (4)");

        assertThat(assertions.query(
                "SELECT x " +
                        "FROM UNNEST(" +
                        "   CAST(ARRAY[ROW(1, 'a'), ROW(2, 'b')] as ARRAY(ROW(a int, b varchar)))," +
                        "   CAST(ARRAY[ROW(3, 'c'), ROW(4, 'd')] as ARRAY(ROW(x int, y varchar))))"))
                .matches("VALUES (3), (4)");
    }

    @Test
    public void testLeftJoinUnnest()
    {
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[1, null]) a(x) LEFT OUTER JOIN UNNEST(x) ON true"))
                .matches("VALUES (ARRAY[1, null], 1), (ARRAY[1, null], null)");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[1, null]) a(x) LEFT OUTER JOIN UNNEST(x) WITH ORDINALITY ON true"))
                .matches("VALUES (ARRAY[1, null], 1, BIGINT '1'), (ARRAY[1, null], null, BIGINT '2')");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[]) a(x) LEFT OUTER JOIN UNNEST(x) ON true"))
                .matches("VALUES (ARRAY[], null)");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[]) a(x) LEFT OUTER JOIN UNNEST(x) WITH ORDINALITY ON true"))
                .matches("VALUES (ARRAY[], null, CAST(NULL AS bigint))");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[1, null]) a(x) LEFT OUTER JOIN UNNEST(x) b(y) ON b.y = 1"))
                .failure().hasMessageMatching("line .*: LEFT JOIN involving UNNEST is only supported with condition ON TRUE");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES 'a', 'b') LEFT JOIN UNNEST(ARRAY[]) ON TRUE"))
                .matches("VALUES ('a', null), ('b', null)");
        assertThat(assertions.query(
                "SELECT id, e FROM (VALUES (1, ARRAY[3,4]), (2, NULL), (3, ARRAY[4]), (4, NULL), (5, ARRAY[]), (6, ARRAY[7,8])) x(id, a) LEFT JOIN UNNEST(a) AS y(e) ON true"))
                .matches("VALUES (1,3), (1,4), (2,NULL), (3,4), (4,NULL), (5,NULL), (6,7), (6,8)");
        // misalignment
        assertThat(assertions.query(
                "SELECT * FROM (VALUES 1) LEFT OUTER JOIN UNNEST (MAP(ARRAY[1, 2], ARRAY['a', 'b']), ARRAY[ROW(3, 'c', true)]) WITH ORDINALITY ON TRUE"))
                .matches("VALUES (1, 1, 'a', 3, 'c', true, BIGINT '1'), (1, 2, 'b', null, null, null, BIGINT '2')");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES 1) LEFT OUTER JOIN UNNEST (MAP(ARRAY[1], ARRAY['a']), ARRAY[true, false], ARRAY[]) WITH ORDINALITY ON TRUE"))
                .matches("VALUES (1, 1, 'a', true, null, BIGINT '1'), (1, null, null, false, null, BIGINT '2')");
    }

    @Test
    public void testRightJoinUnnest()
    {
        // The SQL standard doesn't allow lateral references in UNNEST subquery of RIGHT join.
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[1, null]) a(x) RIGHT OUTER JOIN UNNEST(ARRAY[2, null]) ON true"))
                .matches("VALUES (ARRAY[1, null], 2), (ARRAY[1, null], null)");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[]) a(x) RIGHT OUTER JOIN UNNEST(ARRAY[2, null]) ON true"))
                .matches("VALUES (ARRAY[], 2), (ARRAY[], null)");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[1, null]) a(x) RIGHT OUTER JOIN UNNEST(ARRAY[2, null]) WITH ORDINALITY ON true"))
                .matches("VALUES (ARRAY[1, null], 2, BIGINT '1'), (ARRAY[1, null], null, BIGINT '2')");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[1, null]) a(x) RIGHT OUTER JOIN UNNEST(ARRAY[2, null]) b(y) ON b.y = 1"))
                .failure().hasMessageMatching("line .*: RIGHT JOIN involving UNNEST is only supported with condition ON TRUE");
    }

    @Test
    public void testFullJoinUnnest()
    {
        // The SQL standard doesn't allow lateral references in UNNEST subquery of FULL join.
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[1, null]) a(x) FULL OUTER JOIN UNNEST(ARRAY[2, null]) ON true"))
                .matches("VALUES (ARRAY[1, null], 2), (ARRAY[1, null], null)");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[1, null]) a(x) FULL OUTER JOIN UNNEST(ARRAY[2, null]) WITH ORDINALITY ON true"))
                .matches("VALUES (ARRAY[1, null], 2, BIGINT '1'), (ARRAY[1, null], null, BIGINT '2')");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[]) a(x) FULL OUTER JOIN UNNEST(ARRAY[2, null]) ON true"))
                .matches("VALUES (ARRAY[], 2), (ARRAY[], null)");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[]) a(x) FULL OUTER JOIN UNNEST(ARRAY[2, null]) WITH ORDINALITY ON true"))
                .matches("VALUES (ARRAY[], 2, BIGINT '1'), (ARRAY[], null, BIGINT '2')");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[1, null]) a(x) FULL OUTER JOIN UNNEST(ARRAY[2, null]) b(y) ON b.y = 1"))
                .failure().hasMessageMatching("line .*: FULL JOIN involving UNNEST is only supported with condition ON TRUE");
    }

    @Test
    public void testInnerJoinUnnest()
    {
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[1, null]) a(x) INNER JOIN UNNEST(x) ON true"))
                .matches("VALUES (ARRAY[1, null], 1), (ARRAY[1, null], null)");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[1, null]) a(x) INNER JOIN UNNEST(x) WITH ORDINALITY ON true"))
                .matches("VALUES (ARRAY[1, null], 1, BIGINT '1'), (ARRAY[1, null], null, BIGINT '2')");
        assertions.assertQueryReturnsEmptyResult(
                "SELECT * FROM (VALUES ARRAY[]) a(x) INNER JOIN UNNEST(x) ON true");
        assertions.assertQueryReturnsEmptyResult(
                "SELECT * FROM (VALUES ARRAY[]) a(x) INNER JOIN UNNEST(x) WITH ORDINALITY ON true");
        assertThat(assertions.query(
                "SELECT * FROM (VALUES ARRAY[1, null]) a(x) INNER JOIN UNNEST(x) b(y) ON b.y = 1"))
                .failure().hasMessageMatching("line .*: INNER JOIN involving UNNEST is only supported with condition ON TRUE");
    }

    @Test
    public void testRepeatedExpressions()
    {
        assertThat(assertions.query(
                "SELECT * FROM (VALUES 1) t, UNNEST(ARRAY['a', 'b'], ARRAY['a', 'b']) u (x, y)"))
                .matches("VALUES (1, 'a', 'a'), (1, 'b', 'b')");
    }

    @Test
    public void testUnnestArrays()
    {
        assertThat(assertions.query("SELECT * FROM UNNEST(ARRAY[2, 5])"))
                .matches("VALUES (2), (5)");

        assertThat(assertions.query(
                "SELECT * FROM UNNEST(ARRAY[2, 5], ARRAY['dog', 'cat', 'bird'])"))
                .matches("VALUES (2, 'dog'), (5, 'cat'), (null, 'bird')");

        assertThat(assertions.query(
                "SELECT * FROM UNNEST(ARRAY[2, 5, null], ARRAY['dog', 'cat', 'bird'])"))
                .matches("VALUES (2, 'dog'), (5, 'cat'), (null, 'bird')");
    }

    @Test
    public void testNullRows()
    {
        // This query tries to simulate testArrayOfRowsUnnesterWithNulls e2e
        assertions.execute("SELECT "
                + "     x, y "
                + "FROM "
                + "     (VALUES "
                + "         (transform(sequence(1, 600), x -> CAST(ROW(x, 'a') as ROW(x1 integer, x2 varchar)))), "
                + "         (transform(sequence(1, 400), x -> CAST(NULL as ROW(x1 integer, x2 varchar))))) "
                + "     AS t(a) "
                + "     CROSS JOIN UNNEST(a) t(x, y)");
    }

    @Test
    void testSubqueries()
    {
        assertThat(assertions.query(
                """
                WITH
                    a(x) AS (SELECT ARRAY[1, 2, 3]),
                    b AS (SELECT * FROM (VALUES 4), UNNEST ((SELECT x FROM a)))
                SELECT * FROM b
                """))
                .matches("VALUES (4, 1), (4, 2), (4, 3)");

        assertThat(assertions.query("SELECT * FROM UNNEST ((SELECT ARRAY[1, 2, 3]))"))
                .matches("VALUES 1, 2, 3");

        assertThat(assertions.query("SELECT * FROM (VALUES ARRAY[1, 2, 3]) t(a), UNNEST ((SELECT a))"))
                .matches(
                        """
                        VALUES
                            (ARRAY[1, 2, 3], 1),
                            (ARRAY[1, 2, 3], 2),
                            (ARRAY[1, 2, 3], 3)
                        """);
    }
}
