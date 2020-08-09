/*
 * Copyright 2020 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.vertx.spi.cluster.ignite.impl;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.VertxException;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.spi.cluster.NodeSelector;
import io.vertx.core.spi.cluster.RegistrationInfo;
import io.vertx.core.spi.cluster.RegistrationUpdateEvent;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.events.CacheEvent;
import org.apache.ignite.events.Event;
import org.apache.ignite.lang.IgnitePredicate;

import javax.cache.Cache;
import javax.cache.CacheException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;
import static org.apache.ignite.events.EventType.EVT_CACHE_OBJECT_PUT;
import static org.apache.ignite.events.EventType.EVT_CACHE_OBJECT_REMOVED;

/**
 * @author Thomas Segismont
 * @author Lukas Prettenthaler
 */
public class SubsMapHelper {
  private static final Logger log = LoggerFactory.getLogger(SubsMapHelper.class);
  private final IgniteCache<IgniteRegistrationInfo, Boolean> map;
  private IgnitePredicate<Event> eventListener;

  public SubsMapHelper(Ignite ignite, NodeSelector nodeSelector, VertxInternal vertxInternal) {
    map = ignite.getOrCreateCache("__vertx.subs");
    this.eventListener = event -> this.listen(event, nodeSelector, vertxInternal);

    ignite.events().localListen(this.eventListener, EVT_CACHE_OBJECT_PUT, EVT_CACHE_OBJECT_REMOVED);
  }

  public void get(String address, Promise<List<RegistrationInfo>> promise) {
    try {
      List<RegistrationInfo> infos = map.query(
              new ScanQuery<IgniteRegistrationInfo, Boolean>((k, v) -> k.address().equals(address)))
        .getAll().stream()
        .map(Cache.Entry::getKey)
        .map(IgniteRegistrationInfo::registrationInfo)
        .collect(toList());
      promise.complete(infos);
    } catch (IllegalStateException | CacheException e) {
      promise.fail(new VertxException(e));
    }
  }

  public Future<Void> put(String address, RegistrationInfo registrationInfo) {
    try {
      map.put(new IgniteRegistrationInfo(address, registrationInfo), Boolean.TRUE);
    } catch (IllegalStateException | CacheException e) {
      return Future.failedFuture(new VertxException(e));
    }
    return Future.succeededFuture();
  }

  public void remove(String address, RegistrationInfo registrationInfo, Promise<Void> promise) {
    try {
      map.remove(new IgniteRegistrationInfo(address, registrationInfo));
      promise.complete();
    } catch (IllegalStateException | CacheException e) {
      promise.fail(new VertxException(e));
    }
  }

  public void removeAllForNode(String nodeId) {
    Set<IgniteRegistrationInfo> toRemove =
            map.query(new ScanQuery<IgniteRegistrationInfo, Boolean>((k, v) ->
                    k.registrationInfo().nodeId().equals(nodeId)))
      .getAll().stream()
      .map(Cache.Entry::getKey)
      .collect(Collectors.toSet());
    try {
      map.removeAll(toRemove);
    } catch (IllegalStateException | CacheException t) {
        log.error("Failed to remove all subscribers", t);
    }
  }

  public void leave(Ignite ignite) {
    if (eventListener != null) {
      ignite.events().stopLocalListen(eventListener, EVT_CACHE_OBJECT_PUT, EVT_CACHE_OBJECT_REMOVED);
    }
  }

  boolean listen(Event event, final NodeSelector nodeSelector, final VertxInternal vertxInternal) {
    if (!(event instanceof CacheEvent)) {
      return true;
    }
    CacheEvent cacheEvent = (CacheEvent) event;
    if (!Objects.equals(cacheEvent.cacheName(), map.getName())) {
      return true;
    }

    vertxInternal.<List<RegistrationInfo>>executeBlocking(listPromise -> {
      String address = cacheEvent.<IgniteRegistrationInfo>key().address();
      listPromise.future().onSuccess(registrationInfos ->
              nodeSelector.registrationsUpdated(new RegistrationUpdateEvent(address, registrationInfos)
      ));
      get(address, listPromise);
    });
    return true;
  }
}
