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

import com.spotify.helios.common.QueueableEvent;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.TaskStatusEvent;
import com.spotify.helios.servicescommon.coordination.Paths;

/**
 * Writes task history to ZK.
 */
public class TaskHistoryPath implements HistoryPath {

  private final String hostname;

  public TaskHistoryPath(final String hostname) {
    this.hostname = hostname;
  }

//  public void saveHistoryItem(final TaskStatus status) throws InterruptedException {
//    saveHistoryItem(status, System.currentTimeMillis());
//  }
//
//  public void saveHistoryItem(final TaskStatus status, long timestamp) throws InterruptedException {
//    writer.add(new TaskStatusEvent(status, timestamp, hostname));
//  }

  @Override
  public String getZkEventsPath(final QueueableEvent event) {
    final TaskStatusEvent event1 = (TaskStatusEvent) event;
    final JobId jobId = event1.getStatus().getJob().getId();
    return Paths.historyJobHostEvents(jobId, hostname);
  }
}
