/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.server.lookup;


import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.metamx.common.logger.Logger;
import io.druid.query.lookup.LookupExtractor;
import io.druid.server.lookup.cache.loading.LoadingCache;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class LoadingLookup extends LookupExtractor
{
  private static final Logger LOGGER = new Logger(LoadingLookup.class);

  private final DataFetcher<String, String> dataFetcher;
  private final LoadingCache<String, String> loadingCache;
  private final LoadingCache<String, List<String>> reverseLoadingCache;
  private final AtomicBoolean isOpen;
  private final String id = Integer.toHexString(System.identityHashCode(this));

  public LoadingLookup(
      DataFetcher dataFetcher,
      LoadingCache<String, String> loadingCache,
      LoadingCache<String, List<String>> reverseLoadingCache
  )
  {
    this.dataFetcher = Preconditions.checkNotNull(dataFetcher, "lookup must have a DataFetcher");
    this.loadingCache = Preconditions.checkNotNull(loadingCache, "loading lookup need a cache");
    this.reverseLoadingCache = Preconditions.checkNotNull(reverseLoadingCache, "loading lookup need reverse cache");
    this.isOpen = new AtomicBoolean(true);
  }


  @Override
  public String apply(final String key)
  {
    if (key == null) {
      return null;
    }
    final String presentVal;
    try {
      presentVal = loadingCache.get(key, new Callable<String>()
      {
        @Override
        public String call() throws Exception
        {
          // avoid returning null and return an empty string to cache it.
          return Strings.nullToEmpty(dataFetcher.fetch(key));
        }
      });
      return Strings.emptyToNull(presentVal);
    }
    catch (ExecutionException e) {
      LOGGER.debug("value not found for key [%s]", key);
      return null;
    }
  }

  @Override
  public List<String> unapply(final String value)
  {
    // null value maps to empty list
    if (value == null) {
      return Collections.EMPTY_LIST;
    }
    final List<String> retList;
    try {
      retList = reverseLoadingCache.get(value, new Callable<List<String>>()
      {
        @Override
        public List<String> call() throws Exception
        {
          return dataFetcher.reverseFetchKeys(value);
        }
      });
      return retList;
    }
    catch (ExecutionException e) {
      LOGGER.debug("list of keys not found for value [%s]", value);
      return Collections.EMPTY_LIST;
    }
  }

  public synchronized void close()
  {
    if (isOpen.getAndSet(false)) {
      LOGGER.info("closing loading cache [%s]", id);
      loadingCache.close();
      reverseLoadingCache.close();
    }
  }

  public boolean isOpen()
  {
    return isOpen.get();
  }

  @Override
  public byte[] getCacheKey()
  {
    return LookupExtractionModule.getRandomCacheKey();
  }
}
