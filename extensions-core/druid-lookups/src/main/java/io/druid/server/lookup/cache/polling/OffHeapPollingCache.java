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

package io.druid.server.lookup.cache.polling;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.HTreeMap;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;


public class OffHeapPollingCache<K, V> implements PollingCache<K, V>
{
  private static final DB DB = DBMaker.newMemoryDirectDB().transactionDisable().closeOnJvmShutdown().make();

  private final HTreeMap<K, V> mapCache;
  private final HTreeMap<V, List<K>> reverseCache;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final String cacheName;
  private final String reverseCacheName;

  public OffHeapPollingCache(final Map<K, V> map)
  {
    synchronized (started) {
      this.cacheName = String.format("cache-%s", UUID.randomUUID());
      this.reverseCacheName = String.format("reverseCache-%s", UUID.randomUUID());
      mapCache = DB.createHashMap(cacheName).make();
      reverseCache = DB.createHashMap(reverseCacheName).make();
      mapCache.putAll(map);
      final Set<V> setOfValues = ImmutableSet.copyOf(map.values());
      reverseCache.putAll(Maps.asMap(
          setOfValues, new Function<V, List<K>>()
          {
            @Override
            public List<K> apply(final V input)
            {
              return Lists.newArrayList(Maps.filterKeys(map, new Predicate<K>()
              {
                @Override
                public boolean apply(K key)
                {
                  V retVal = map.get(key);
                  if (retVal == null) {
                    return false;
                  }
                  return retVal.equals(input);
                }
              }).keySet());
            }
          }));
      started.getAndSet(true);
    }
  }

  @Override
  public V get(K key)
  {
    return mapCache.get(key);
  }

  @Override
  public List<K> getKeys(V value)
  {
    final List<K> listOfKey = reverseCache.get(value);
    if (listOfKey == null) {
      reverseCache.put(value, Collections.<K>emptyList());
      return Collections.emptyList();
    }
    return listOfKey;
  }

  @Override
  public void close()
  {
    synchronized (started) {
      if (started.getAndSet(false)) {
        DB.delete(cacheName);
        DB.delete(reverseCacheName);
      }
    }
  }

  public static class OffHeapPollingCacheProvider<K, V> implements PollingCacheFactory<K, V>
  {
    @Override
    public PollingCache makeOf(Map<K, V> map)
    {
      return new OffHeapPollingCache<K, V>(map);
    }

    @Override
    public boolean equals(Object obj)
    {
      return (obj instanceof OffHeapPollingCacheProvider);
    }
  }
}
