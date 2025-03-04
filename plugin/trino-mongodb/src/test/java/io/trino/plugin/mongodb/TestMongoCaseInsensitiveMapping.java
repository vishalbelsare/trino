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
package io.trino.plugin.mongodb;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.QueryRunner;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;

import java.util.Map;

import static io.trino.plugin.mongodb.MongoQueryRunner.createMongoClient;
import static io.trino.testing.TestingNames.randomNameSuffix;
import static java.util.Locale.ENGLISH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT;

@TestInstance(PER_CLASS)
@Execution(CONCURRENT)
public class TestMongoCaseInsensitiveMapping
        extends AbstractTestQueryFramework
{
    private final MongoServer server;
    private final MongoClient client;

    public TestMongoCaseInsensitiveMapping()
    {
        server = new MongoServer();
        client = createMongoClient(server);
    }

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return MongoQueryRunner.builder(server)
                .addConnectorProperties(Map.of("mongodb.case-insensitive-name-matching", "true"))
                .build();
    }

    @AfterAll
    public final void destroy()
    {
        server.close();
        client.close();
    }

    @Test
    public void testCaseInsensitive()
    {
        MongoCollection<Document> collection = client.getDatabase("testCase").getCollection("testInsensitive");
        collection.insertOne(new Document(ImmutableMap.of("Name", "abc", "Value", 1)));

        assertQuery("SHOW SCHEMAS IN mongodb LIKE 'testcase'", "SELECT 'testcase'");
        assertQuery("SHOW TABLES IN testcase", "SELECT 'testinsensitive'");
        assertQuery(
                "SHOW COLUMNS FROM testcase.testInsensitive",
                "VALUES ('name', 'varchar', '', ''), ('value', 'bigint', '', '')");

        assertQuery("SELECT name, value FROM testcase.testinsensitive", "SELECT 'abc', 1");
        assertUpdate("INSERT INTO testcase.testinsensitive VALUES('def', 2)", 1);

        assertQuery("SELECT value FROM testcase.testinsensitive WHERE name = 'def'", "SELECT 2");
        assertUpdate("DROP TABLE testcase.testinsensitive");
        assertQueryReturnsEmptyResult("SHOW TABLES IN testcase");

        assertUpdate("DROP SCHEMA testcase");
        assertQueryReturnsEmptyResult("SHOW SCHEMAS IN mongodb LIKE 'testcase'");
    }

    @Test
    public void testCaseInsensitiveRenameTable()
    {
        MongoCollection<Document> collection = client.getDatabase("testCase_RenameTable").getCollection("testInsensitive_RenameTable");
        collection.insertOne(new Document(ImmutableMap.of("value", 1)));
        assertQuery("SHOW TABLES IN testcase_renametable", "SELECT 'testinsensitive_renametable'");
        assertQuery("SELECT value FROM testcase_renametable.testinsensitive_renametable", "SELECT 1");

        assertUpdate("ALTER TABLE testcase_renametable.testinsensitive_renametable RENAME TO testcase_renametable.testinsensitive_renamed_table");

        assertQuery("SHOW TABLES IN testcase_renametable", "SELECT 'testinsensitive_renamed_table'");
        assertQuery("SELECT value FROM testcase_renametable.testinsensitive_renamed_table", "SELECT 1");
        assertUpdate("DROP TABLE testcase_renametable.testinsensitive_renamed_table");
    }

    @Test
    public void testNonLowercaseViewName()
    {
        // Case insensitive schema name
        MongoCollection<Document> collection = client.getDatabase("NonLowercaseSchema").getCollection("test_collection");
        collection.insertOne(new Document(ImmutableMap.of("Name", "abc", "Value", 1)));

        client.getDatabase("NonLowercaseSchema").createView("lowercase_view", "test_collection", ImmutableList.of());
        assertQuery("SELECT value FROM nonlowercaseschema.lowercase_view WHERE name = 'abc'", "SELECT 1");

        // Case insensitive view name
        collection = client.getDatabase("test_database").getCollection("test_collection");
        collection.insertOne(new Document(ImmutableMap.of("Name", "abc", "Value", 1)));

        client.getDatabase("test_database").createView("NonLowercaseView", "test_collection", ImmutableList.of());
        assertQuery("SELECT value FROM test_database.nonlowercaseview WHERE name = 'abc'", "SELECT 1");

        // Case insensitive schema and view name
        client.getDatabase("NonLowercaseSchema").createView("NonLowercaseView", "test_collection", ImmutableList.of());
        assertQuery("SELECT value FROM nonlowercaseschema.nonlowercaseview WHERE name = 'abc'", "SELECT 1");

        assertUpdate("DROP TABLE nonlowercaseschema.lowercase_view");
        assertUpdate("DROP TABLE test_database.nonlowercaseview");
        assertUpdate("DROP TABLE nonlowercaseschema.test_collection");
        assertUpdate("DROP TABLE test_database.test_collection");
        assertUpdate("DROP TABLE nonlowercaseschema.nonlowercaseview");
    }

    @Test
    public void testNativeQueryWithCaseInSensitiveNameMatch()
    {
        String tableName = "Test_Case_Insensitive" + randomNameSuffix();
        String schemaName = "Test_Case_Insensitive_Schema" + randomNameSuffix();
        client.getDatabase(schemaName).getCollection(tableName).insertOne(new Document("field", "hello"));

        assertThat(query("SELECT * FROM TABLE(mongodb.system.query(database => '" + schemaName.toLowerCase(ENGLISH) + "', collection => '" + tableName.toLowerCase(ENGLISH) + "', filter => '{}'))"))
                .matches("VALUES CAST('hello' AS VARCHAR)");
    }
}
