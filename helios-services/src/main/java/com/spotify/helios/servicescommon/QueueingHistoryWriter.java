/*
 * Copyright (c) 2014 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.helios.servicescommon;

import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.MoreExecutors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.spotify.helios.common.QueueableEvent;
import com.spotify.helios.servicescommon.coordination.PathFactory;
import com.spotify.helios.servicescommon.coordination.ZooKeeperClient;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.MessageDigestAlgorithms;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Writes historical events to ZooKeeper. We attempt to gracefully handle
 * the case where ZK is down by persisting events in a backing file.
 *
 * Theory of operation:
 *
 * 1. Adding an event should never block for any significant amount of time. Specifically, it
 *    should not block on ZK being in any particular state, and ideally not while a file write is
 *    occurring, as the file may get large if ZK has been away for a long time.
 *
 * 2. We set limits on the maximum number of events stored at any particular ZK path, and also the
 *    overall total number of events.
 *
 * To use this class, implement a QueueingHistoryWriter for a specific type of event and call the
 * add(QueueableEvent) method to add an event.
 */
public class QueueingHistoryWriter extends AbstractIdleService implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(QueueingHistoryWriter.class);

  public static final int DEFAULT_MAX_EVENTS_PER_PATH = 30;
  public static final int DEFAULT_MAX_TOTAL_EVENTS = 600;

  private static final int DEFAULT_MAX_QUEUE_SIZE = 30;

  private final ScheduledExecutorService zkWriterExecutor =
      MoreExecutors.getExitingScheduledExecutorService(
          (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1), 0, SECONDS);
  private final Map<String, InterProcessMutex> mutexes = Maps.newHashMap();

  private final ConcurrentMap<String, Deque<QueueableEvent>> events;
  private final AtomicInteger count;
  private final ZooKeeperClient client;
  private final
  PersistentAtomicReference<ConcurrentMap<String, Deque<QueueableEvent>>> backingStore;
  private final MessageDigest messageDigest;
  private final HistoryPath historyPath;

  /**
   * Generate a hex string of the hash sum of bytes.
   * @param bytes An array of bytes
   * @return Key for the event.
   */
  private String getKey(final byte[] bytes) {
    return Hex.encodeHexString(messageDigest.digest(bytes));
  }

  public int getMaxEventsPerPath() {
    return DEFAULT_MAX_EVENTS_PER_PATH;
  }

  public int getMaxTotalEvents() {
    return DEFAULT_MAX_TOTAL_EVENTS;
  }

  protected int getMaxQueueSize() {
    return DEFAULT_MAX_QUEUE_SIZE;
  }

  public QueueingHistoryWriter(final ZooKeeperClient client, final Path backingFile,
                               final HistoryPath historyPath)
      throws IOException, InterruptedException, NoSuchAlgorithmException {
    this.client = checkNotNull(client, "client");
    this.backingStore = PersistentAtomicReference.create(
        checkNotNull(backingFile, "backingFile"),
        new TypeReference<ConcurrentMap<String, Deque<QueueableEvent>>>(){},
        new Supplier<ConcurrentMap<String, Deque<QueueableEvent>>>() {
          @Override public ConcurrentMap<String, Deque<QueueableEvent>> get() {
            return Maps.newConcurrentMap();
          }
        });
    this.events = backingStore.get();

    // Clean out any errant null values.  Normally shouldn't have any, but we did have a few
    // where it happened, and this will make sure we can get out of a bad state if we get into it.
    final ImmutableSet<String> curKeys = ImmutableSet.copyOf(this.events.keySet());
    for (Object key : curKeys) {
      if (this.events.get(key) == null) {
        this.events.remove(key);
      }
    }

    int eventCount = 0;
    for (Deque<QueueableEvent> deque : events.values()) {
      eventCount += deque.size();
    }
    this.count = new AtomicInteger(eventCount);
    this.messageDigest = MessageDigest.getInstance(MessageDigestAlgorithms.MD5);
    this.historyPath = historyPath;
  }

  @Override
  protected void startUp() throws Exception {
    zkWriterExecutor.scheduleAtFixedRate(this, 1, 1, TimeUnit.SECONDS);
  }

  @Override
  protected void shutDown() throws Exception {
    zkWriterExecutor.shutdownNow();
    zkWriterExecutor.awaitTermination(1, TimeUnit.MINUTES);
  }

  /**
   * Add an event to the queue to be written to ZooKeeper.
   * @param event {@link QueueableEvent}
   * @throws InterruptedException
   */
  public void add(final QueueableEvent event) throws InterruptedException {
    // If too many "globally", toss them
    while (count.get() >= getMaxTotalEvents()) {
      getNext();
    }

    final String key = getKey(event.data());
    final Deque<QueueableEvent> deque = getDeque(key);

    synchronized (deque) {
      // if too many in the particular deque, toss them
      while (deque.size() >= getMaxQueueSize()) {
        deque.remove();
        count.decrementAndGet();
      }
      deque.add(event);
      count.incrementAndGet();
    }

    try {
      backingStore.set(events);
    } catch (ClosedByInterruptException e) {
      log.debug("Writing task status event to backing store was interrupted");
    } catch (IOException e) { // We are best effort after all...
      log.warn("Failed to write task status event to backing store", e);
    }
  }

  private Deque<QueueableEvent> getDeque(final String key) {
    synchronized (events) {
      final Deque<QueueableEvent> deque = events.get(key);
      if (deque == null) {  // try more assertively to get a deque
        final ConcurrentLinkedDeque<QueueableEvent> newDeque =
            new ConcurrentLinkedDeque<QueueableEvent>();
        events.put(key, newDeque);
        return newDeque;
      }
      return deque;
    }
  }

  private QueueableEvent getNext() {
    // Some explanation: We first find the eldest event from amongst the queues (ok, they're
    // deques, but we really use it as a put back queue), and only then to we try to get
    // a lock on the relevant queue from whence we got the event.  Assuming that all worked
    // *and* that the event we have wasn't rolled off due to max-size limitations, we then
    // pull the event off the queue and return it.  We're basically doing optimistic concurrency,
    // and skewing things so that adding to this should be cheap.

    while (true) {
      final QueueableEvent current = findEldestEvent();

      // Didn't find anything that needed processing?
      if (current == null) {
        return null;
      }

      final String key = getKey(current.data());
      final Deque<QueueableEvent> deque = events.get(key);
      if (deque == null) {
        // shouldn't happen because we should be the only one pulling events off, but....
        continue;
      }

      synchronized (deque) {
        if (!deque.peek().equals(current)) {
          // event got rolled off, try again
          continue;
        }

        // Pull it off the queue and be paranoid.
        final QueueableEvent newCurrent = deque.poll();
        count.decrementAndGet();
        checkState(current.equals(newCurrent), "current should equal newCurrent");
        // Safe because this is the *only* place we hold these two locks at the same time.
        synchronized (events) {
          // Extra paranoia: curDeque should always == deque
          final Deque<QueueableEvent> curDeque = events.get(key);
          if (curDeque != null && curDeque.isEmpty()) {
            events.remove(key);
          }
        }
        return current;
      }
    }
  }

  public boolean isEmpty() {
    return count.get() == 0;
  }

  private void putBack(final QueueableEvent event) {
    final String key = getKey(event.data());
    final Deque<QueueableEvent> queue = getDeque(key);
    synchronized (queue) {
      if (queue.size() >= getMaxQueueSize()) {
        // already full, just toss the event
        return;
      }
      queue.push(event);
      count.incrementAndGet();
    }
  }

  private QueueableEvent findEldestEvent() {
    // We don't lock anything because in the worst case, we just put things in out of order which
    // while not perfect, won't cause any actual harm.  Out of order meaning between jobids, not
    // within the same job id.  Whether this is the best strategy (as opposed to fullest deque)
    // is arguable.
    QueueableEvent current = null;
    for (Deque<QueueableEvent> queue : events.values()) {
      if (queue == null) {
        continue;
      }
      final QueueableEvent event = queue.peek();
      if (current == null || (event.timestamp() < current.timestamp())) {
        current = event;
      }
    }
    return current;
  }

  private String getZkEventPath(final String eventsPath, final long timestamp) {
    return new PathFactory(eventsPath).path(String.valueOf(timestamp));
  }

  @Override
  public void run() {
    while (true) {
      final QueueableEvent event = getNext();
      if (event == null) {
        return;
      }

      if (!tryWriteToZooKeeper(event)) {
        putBack(event);
      }
    }
  }

  private boolean tryWriteToZooKeeper(QueueableEvent event) {
    final String eventsPath = historyPath.getZkEventsPath(event);

    if (!mutexes.containsKey(eventsPath)) {
      mutexes.put(eventsPath, new InterProcessMutex(client.getCuratorFramework(),
                                                    eventsPath + "_lock"));
    }

    final InterProcessMutex mutex = mutexes.get(eventsPath);
    try {
      mutex.acquire();
    } catch (Exception e) {
      log.error("error acquiring lock for event {} - {}", getKey(event.data()), e);
      return false;
    }

    try {
      log.debug("writing queued event to zookeeper {} {}", getKey(event.data()), event.timestamp());

      client.ensurePath(eventsPath);
      client.createAndSetData(getZkEventPath(eventsPath, event.timestamp()), event.data());

      // See if too many
      final List<String> events = client.getChildren(eventsPath);
      if (events.size() > getMaxEventsPerPath()) {
        trimStatusEvents(events, eventsPath);
      }
    } catch (NodeExistsException e) {
      // Ahh, the two generals problem...  We handle by doing nothing since the thing
      // we wanted in, is in.
      log.debug("event we wanted in is already there");
    } catch (ConnectionLossException e) {
      log.warn("Connection lost while putting event into zookeeper, will retry");
      return false;
    } catch (KeeperException e) {
      log.error("Error putting event into zookeeper, will retry", e);
      return false;
    } finally {
      try {
        mutex.release();
      } catch (Exception e) {
        log.error("error releasing lock for event {} - {}", getKey(event.data()), e);
      }
    }

    return true;
  }

  private void trimStatusEvents(final List<String> events, final String eventsPath) {
    // All this to sort numerically instead of lexically....
    final List<Long> eventsAsLongs = Lists.newArrayList(Iterables.transform(events,
      new Function<String, Long>() {
      @Override
      public Long apply(String name) {
        return Long.valueOf(name);
      }
    }));
    Collections.sort(eventsAsLongs);

    for (int i = 0; i < (eventsAsLongs.size() - getMaxEventsPerPath()); i++) {
      try {
        client.delete(getZkEventPath(eventsPath, eventsAsLongs.get(i)));
      } catch (KeeperException e) {
        log.warn("failure deleting overflow of status events - we're hoping a later"
            + " execution will fix", e);
      }
    }
  }
}
