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

package com.spotify.helios.auth;

import io.dropwizard.jersey.setup.JerseyEnvironment;

/**
 * @param <C> the type of Credentials used
 */
public interface AuthenticationPlugin<C> {

  /**
   * The name of the scheme that this plugin provides.
   * <p>
   * When the Helios master starts up and attempts to load all configured authentication plugins,
   * it will compare the return value of this method against the <code>--auth-scheme</code>
   * argument that it was started with.
   * </p>
   */
  String schemeName();

  ServerAuthentication<C> serverAuthentication();

  ClientAuthentication<C> clientAuthentication();

  interface ServerAuthentication<C> {

    /**
     * An Authenticator instance to use when authenticating HTTP requests to Helios.
     * <p>
     * Helio's Authentication support builds on top of the {@link io.dropwizard.auth.Authenticator
     * Authenticator interface from Dropwizard} to add in a method for transforming HTTP headers
     * into a "credentials" object. The latter is then fed into the {@link
     * Authenticator#authenticate(Object)} method (defined in the dropwizard Authenticator
     * interface) to actually authenticate the request.
     * </p>
     */
    Authenticator<C> authenticator();

    /**
     * A hook for implementations to register additional Jersey components, such as Resource
     * classes
     * for multi-stepped authentication handshakes.
     */
    void registerAdditionalJerseyComponents(JerseyEnvironment env);
  }

  interface ClientAuthentication<C> {
    // TODO (mbrown): have an interface!
  }
}
