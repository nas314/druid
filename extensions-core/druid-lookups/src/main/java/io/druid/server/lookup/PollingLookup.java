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

package io.druid.server.lookup;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.metamx.common.ISE;
import com.metamx.common.logger.Logger;
import io.druid.query.lookup.LookupExtractor;
import io.druid.server.lookup.cache.polling.OnHeapPollingCache;
import io.druid.server.lookup.cache.polling.PollingCache;
import io.druid.server.lookup.cache.polling.PollingCacheFactory;
import org.joda.time.Period;

import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class PollingLookup extends LookupExtractor
{
  private static final Logger LOGGER = new Logger(PollingLookup.class);

  private final Period pollPeriod;

  private final DataFetcher dataFetcher;

  private final PollingCacheFactory cacheFactory;

  private final AtomicReference<CacheRefKeeper> refOfCacheKeeper = new AtomicReference<>();


  private final ListeningScheduledExecutorService scheduledExecutorService;

  private final AtomicBoolean isOpen = new AtomicBoolean(false);

  private final ListenableFuture<?> pollFuture;

  public PollingLookup(
      Period pollPeriod,
      DataFetcher dataFetcher,
      PollingCacheFactory cacheFactory
  )
  {

    this.pollPeriod = pollPeriod == null ? Period.ZERO : pollPeriod;
    this.dataFetcher = Preconditions.checkNotNull(dataFetcher);
    this.cacheFactory = cacheFactory == null ? new OnHeapPollingCache.OnHeapPollingCacheProvider() : cacheFactory;
    refOfCacheKeeper.set(new CacheRefKeeper(this.cacheFactory.makeOf(dataFetcher.fetchAll())));
    if (getPollMs() > 0) {
      scheduledExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor(
          new ThreadFactoryBuilder()
              .setDaemon(true)
              .setNameFormat("PollingLookup-%d")
              .setPriority(Thread.MIN_PRIORITY)
              .build()
      ));
      pollFuture = scheduledExecutorService.scheduleAtFixedRate(
          pollAndSwap(),
          getPollMs(),
          getPollMs(),
          TimeUnit.MILLISECONDS
      );
    } else {
      scheduledExecutorService = null;
      pollFuture = null;
    }
    this.isOpen.set(true);
  }


  public void close()
  {
    LOGGER.info("Closing lookup");
    synchronized (isOpen) {
      isOpen.getAndSet(false);
      CacheRefKeeper cacheRefKeeper = refOfCacheKeeper.getAndSet(null);
      if (cacheRefKeeper != null) {
        cacheRefKeeper.doneWithIt();
      }
      if (pollFuture != null) {
        pollFuture.cancel(true);
        scheduledExecutorService.shutdown();
      }
    }
  }

  @Override
  public String apply(@NotNull String key)
  {
    CacheRefKeeper cacheRefKeeper = refOfCacheKeeper.get();
    if (cacheRefKeeper == null) {
      throw new ISE("Cache reference is null WTF");
    }
    PollingCache cache = cacheRefKeeper.get();
    try {
      if (cache == null) {
        // it must've been closed after swapping while I was getting it.  Try again.
        return this.apply(key);
      }
      return Strings.emptyToNull((String) cache.get(key));
    }
    finally {
      if (cacheRefKeeper != null && cache != null) {
        cacheRefKeeper.doneWithIt();
      }
    }
  }

  @Override
  public List<String> unapply(final String value)
  {
    CacheRefKeeper cacheRefKeeper = refOfCacheKeeper.get();
    if (cacheRefKeeper == null) {
      throw new ISE("lookup is closed");
    }
    PollingCache cache = cacheRefKeeper.get();
    try {
      if (cache == null) {
        // it must've been closed after swapping while I was getting it.  Try again.
        return this.unapply(value);
      }
      return cache.getKeys(value);
    }
    finally {
      if (cacheRefKeeper != null && cache != null) {
        cacheRefKeeper.doneWithIt();
      }
    }
  }

  @Override
  public byte[] getCacheKey()
  {
    return LookupExtractionModule.getRandomCacheKey();
  }

  private Runnable pollAndSwap()
  {
    return new Runnable()
    {
      @Override
      public void run()
      {
        LOGGER.debug("Polling and swapping of lookup");
        CacheRefKeeper newCacheKeeper = new CacheRefKeeper(cacheFactory.makeOf(dataFetcher.fetchAll()));
        CacheRefKeeper oldCacheKeeper = refOfCacheKeeper.getAndSet(newCacheKeeper);
        if (oldCacheKeeper != null) {
          oldCacheKeeper.doneWithIt();
        }
      }
    };
  }

  private long getPollMs()
  {
    return pollPeriod.toStandardDuration().getMillis();
  }


  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PollingLookup)) {
      return false;
    }

    PollingLookup that = (PollingLookup) o;

    if (!pollPeriod.equals(that.pollPeriod)) {
      return false;
    }
    if (!dataFetcher.equals(that.dataFetcher)) {
      return false;
    }
    return cacheFactory.equals(that.cacheFactory);

  }

  public boolean isOpen()
  {
    return isOpen.get();
  }


  protected static class CacheRefKeeper
  {
    private final PollingCache pollingCache;
    private volatile Long refCounts = 0L;

    CacheRefKeeper(PollingCache pollingCache) {this.pollingCache = pollingCache;}

    PollingCache get()
    {
      synchronized (refCounts) {
        if (refCounts < 0) {
          return null;
        }
        refCounts += 1;
        return pollingCache;
      }
    }

    void doneWithIt()
    {
      synchronized (refCounts) {
        if (refCounts == 0) {
          pollingCache.close();
        }
        refCounts -= 1;
      }
    }
  }

}
