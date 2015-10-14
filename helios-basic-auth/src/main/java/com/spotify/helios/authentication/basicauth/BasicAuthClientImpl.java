/*
 * Copyright (c) 2015 Spotify AB.
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

package com.spotify.helios.authentication.basicauth;


import com.spotify.helios.authentication.AuthClient;
import com.spotify.helios.authentication.HeliosAuthException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;

class BasicAuthClientImpl implements AuthClient {

  private static final Logger log = LoggerFactory.getLogger(BasicInjectableProvider.class);

  private final Path privateKeyPath;
  private final List<URI> authServerUris;
  private final Client client;
  private String token;

  public BasicAuthClientImpl(final Path privateKeyPath, final List<URI> authServerUris) {
    this.privateKeyPath = privateKeyPath;
    this.authServerUris = authServerUris;
    this.client = ClientBuilder.newClient();
  }

  public String getToken(final String username) throws HeliosAuthException {
    return null;
  }
}

