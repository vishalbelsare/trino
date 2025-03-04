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
package io.trino.plugin.mysql;

import com.google.common.collect.ImmutableMap;
import io.trino.plugin.jdbc.BaseAutomaticJoinPushdownTest;
import io.trino.testing.MaterializedRow;
import io.trino.testing.QueryRunner;
import org.junit.jupiter.api.Test;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assumptions.abort;

public class TestMySqlAutomaticJoinPushdown
        extends BaseAutomaticJoinPushdownTest
{
    private TestingMySqlServer mySqlServer;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        mySqlServer = closeAfterClass(new TestingMySqlServer());

        return MySqlQueryRunner.builder(mySqlServer)
                .addConnectorProperties(ImmutableMap.<String, String>builder()
                        .put("metadata.cache-ttl", "0m")
                        .put("metadata.cache-missing", "false")
                        .buildOrThrow())
                .build();
    }

    @Test
    @Override
    public void testJoinPushdownWithEmptyStatsInitially()
    {
        abort("MySQL statistics are automatically collected");
    }

    @Override
    protected void gatherStats(String tableName)
    {
        for (MaterializedRow row : computeActual("SHOW COLUMNS FROM " + tableName)) {
            String columnName = (String) row.getField(0);
            String columnType = (String) row.getField(1);
            if (columnType.startsWith("varchar")) {
                // varchar index require length
                continue;
            }
            onRemoteDatabase(format("CREATE INDEX \"%2$s\" ON %1$s (\"%2$s\")", tableName, columnName).replace("\"", "`"));
        }
        onRemoteDatabase("ANALYZE TABLE " + tableName.replace("\"", "`"));
    }

    protected void onRemoteDatabase(String sql)
    {
        mySqlServer.execute(sql);
    }
}
