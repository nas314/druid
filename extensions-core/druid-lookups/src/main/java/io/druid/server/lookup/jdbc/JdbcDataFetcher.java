/*
 *
 *  Licensed to Metamarkets Group Inc. (Metamarkets) under one
 *  or more contributor license agreements. See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership. Metamarkets licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 * /
 */

package io.druid.server.lookup.jdbc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.metamx.common.logger.Logger;
import io.druid.metadata.MetadataStorageConnectorConfig;
import io.druid.server.lookup.DataFetcher;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.tweak.HandleCallback;
import org.skife.jdbi.v2.util.StringMapper;

import java.util.List;
import java.util.Map;

public class JdbcDataFetcher implements DataFetcher<String, String>
{
  private static final Logger LOGGER = new Logger(JdbcDataFetcher.class);

  @JsonProperty
  private final MetadataStorageConnectorConfig connectorConfig;
  @JsonProperty
  private final String table;
  @JsonProperty
  private final String keyColumn;
  @JsonProperty
  private final String valueColumn;

  private final String fetchAllQuery;
  private final String fetchQuery;
  private final String reverseFetchQuery;

  public JdbcDataFetcher(
      @JsonProperty("connectorConfig") MetadataStorageConnectorConfig connectorConfig,
      @JsonProperty("table") String table,
      @JsonProperty("keyColumn") String keyColumn,
      @JsonProperty("valueColumn") String valueColumn
  )
  {
    this.connectorConfig = Preconditions.checkNotNull(connectorConfig, "connectorConfig");
    Preconditions.checkNotNull(connectorConfig.getConnectURI(), "connectorConfig.connectURI");
    this.table = Preconditions.checkNotNull(table, "table");
    this.keyColumn = Preconditions.checkNotNull(keyColumn, "keyColumn");
    this.valueColumn = Preconditions.checkNotNull(valueColumn, "valueColumn");

    this.fetchAllQuery = String.format(
        "SELECT %s, %s FROM %s",
        this.keyColumn,
        this.valueColumn,
        this.table
    );
    this.fetchQuery = String.format(
        "SELECT %s FROM %s WHERE %s = :val",
        this.valueColumn,
        this.table,
        this.keyColumn
    );
    this.reverseFetchQuery = String.format(
        "SELECT %s FROM %s WHERE %s = :val",
        this.keyColumn,
        this.table,
        this.valueColumn
    );
  }

  @Override
  public Map<String, String> fetchAll()
  {
    final DBI dbi = getDbi();
    return dbi.withHandle(new HandleCallback<ImmutableMap<String, String>>()
                          {
                            @Override
                            public ImmutableMap<String, String> withHandle(Handle handle) throws Exception
                            {
                              final ImmutableMap.Builder mapBuilder = ImmutableMap.builder();
                              List<Map<String, Object>> result = handle.select(fetchAllQuery);
                              LOGGER.debug("Polled [%d] row(s) from table [%s]", result.size(), table);
                              for (Map<String, Object> map : result) {
                                mapBuilder.put(
                                    map.get(keyColumn),
                                    map.get(Strings.nullToEmpty(valueColumn))
                                );
                              }
                              return mapBuilder.build();
                            }
                          }
    );
  }

  @Override
  public String fetch(final String key)
  {
    final DBI dbi = getDbi();
    List<String> pairs = dbi.withHandle(
        new HandleCallback<List<String>>()
        {
          @Override
          public List<String> withHandle(Handle handle) throws Exception
          {
            return handle.createQuery(fetchQuery)
                         .bind("val", key)
                         .map(StringMapper.FIRST)
                         .list();
          }
        }
    );
    if (pairs.isEmpty()) {
      return null;
    }
    return Strings.nullToEmpty(pairs.get(0));
  }

  @Override
  public Map<String, String> fetch(final Iterable<String> keys)
  {
    //@TODO this implem if very naive can be optimized by issuing a fetchAllQuery with all the keys
    ImmutableMap.Builder mapBuilder = new ImmutableMap.Builder();
    for (String key : keys
        ) {
      String retVal = fetch(key);
      if (retVal != null) {
        mapBuilder.put(key, retVal);
      }
    }
    return mapBuilder.build();
  }

  @Override
  public List<String> reverseFetchKeys(final String value)
  {
    final DBI dbi = getDbi();
    List<String> results = dbi.withHandle(new HandleCallback<List<String>>()
    {
      @Override
      public List<String> withHandle(Handle handle) throws Exception
      {
        return handle.createQuery(reverseFetchQuery)
                     .bind("val", value)
                     .map(StringMapper.FIRST)
                     .list();
      }
    });
    return results;
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (!(o instanceof JdbcDataFetcher)) {
      return false;
    }

    JdbcDataFetcher that = (JdbcDataFetcher) o;

    if (!connectorConfig.equals(that.connectorConfig)) {
      return false;
    }
    if (!table.equals(that.table)) {
      return false;
    }
    if (!keyColumn.equals(that.keyColumn)) {
      return false;
    }
    return valueColumn.equals(that.valueColumn);

  }

  private DBI getDbi()
  {
    return new DBI(
        connectorConfig.getConnectURI(),
        connectorConfig.getUser(),
        connectorConfig.getPassword()
    );
  }
}
