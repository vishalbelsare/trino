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
package io.trino.plugin.deltalake;

import io.trino.testing.BaseDynamicPartitionPruningTest;
import io.trino.testing.QueryRunner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;

import static java.lang.String.format;
import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assumptions.abort;

public class TestDeltaLakeDynamicPartitionPruningTest
        extends BaseDynamicPartitionPruningTest
{
    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return DeltaLakeQueryRunner.builder()
                .setExtraProperties(EXTRA_PROPERTIES)
                .addDeltaProperty("delta.dynamic-filtering.wait-timeout", "1h")
                .addDeltaProperty("delta.enable-non-concurrent-writes", "true")
                .setInitialTables(REQUIRED_TABLES)
                .build();
    }

    @Test
    @Override
    public void testJoinDynamicFilteringMultiJoinOnBucketedTables()
    {
        abort("Delta Lake does not support bucketing");
    }

    @Override
    protected void createLineitemTable(String tableName, List<String> columns, List<String> partitionColumns)
    {
        String sql = format(
                "CREATE TABLE %s WITH (partitioned_by=ARRAY[%s]) AS SELECT %s FROM tpch.tiny.lineitem",
                tableName,
                partitionColumns.stream().map(column -> "'" + column + "'").collect(joining(",")),
                String.join(",", columns));
        getQueryRunner().execute(sql);
    }

    @Override
    protected void createPartitionedTable(String tableName, List<String> columns, List<String> partitionColumns)
    {
        String sql = format(
                "CREATE TABLE %s (%s) WITH (location='%s', partitioned_by=ARRAY[%s])",
                tableName,
                String.join(",", columns),
                createTableLocation(tableName),
                partitionColumns.stream().map(column -> "'" + column + "'").collect(joining(",")));
        getQueryRunner().execute(sql);
    }

    @Override
    protected void createPartitionedAndBucketedTable(String tableName, List<String> columns, List<String> partitionColumns, List<String> bucketColumns)
    {
        throw new UnsupportedOperationException();
    }

    private static URI createTableLocation(String tableName)
    {
        try {
            return Files.createTempDirectory(tableName).toFile().toURI();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
