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
package io.trino.plugin.hive.metastore.thrift;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.NotNull;

import java.net.URI;
import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;

public class StaticMetastoreConfig
{
    public static final String HIVE_METASTORE_USERNAME = "hive.metastore.username";
    private List<URI> metastoreUris;
    private String metastoreUsername;

    @NotNull
    public List<URI> getMetastoreUris()
    {
        return metastoreUris;
    }

    @Config("hive.metastore.uri")
    @ConfigDescription("Hive metastore URIs (comma separated)")
    public StaticMetastoreConfig setMetastoreUris(List<String> uris)
    {
        if (uris == null) {
            this.metastoreUris = null;
            return this;
        }

        this.metastoreUris = uris.stream()
                .map(URI::create)
                .collect(toImmutableList());

        return this;
    }

    public String getMetastoreUsername()
    {
        return metastoreUsername;
    }

    @Config(HIVE_METASTORE_USERNAME)
    @ConfigDescription("Optional username for accessing the Hive metastore")
    public StaticMetastoreConfig setMetastoreUsername(String metastoreUsername)
    {
        this.metastoreUsername = metastoreUsername;
        return this;
    }

    @AssertFalse(message = "'hive.metastore.uri' cannot contain both http and https URI schemes")
    public boolean isMetastoreHttpUrisValid()
    {
        if (metastoreUris == null) {
            // metastoreUris is required, but that's validated on the getter
            return false;
        }
        boolean hasHttpMetastore = metastoreUris.stream().anyMatch(uri -> "http".equalsIgnoreCase(uri.getScheme()));
        boolean hasHttpsMetastore = metastoreUris.stream().anyMatch(uri -> "https".equalsIgnoreCase(uri.getScheme()));
        if (hasHttpsMetastore || hasHttpMetastore) {
            return hasHttpMetastore && hasHttpsMetastore;
        }
        return false;
    }

    @AssertFalse(message = "'hive.metastore.uri' cannot contain both http(s) and thrift URI schemes")
    public boolean isEitherThriftOrHttpMetastore()
    {
        if (metastoreUris == null) {
            // metastoreUris is required, but that's validated on the getter
            return false;
        }
        boolean hasHttpMetastore = metastoreUris.stream().anyMatch(uri -> "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        boolean hasThriftMetastore = metastoreUris.stream().anyMatch(uri -> "thrift".equalsIgnoreCase(uri.getScheme()));
        return hasHttpMetastore && hasThriftMetastore;
    }
}
