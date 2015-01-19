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

import com.spotify.helios.secretprovider.NopSecretProvider;
import com.spotify.helios.secretprovider.NopSecretProviderFactory;
import com.spotify.helios.secretprovider.SecretProvider;
import com.spotify.helios.secretprovider.SecretProviderFactory;
import com.spotify.helios.secretprovider.SecretProviderLoader;
import com.spotify.helios.secretprovider.SecretProviderLoadingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Loads in the secret provider factory plugin (if specified) and returns an
 * appropriate {@link com.spotify.helios.secretprovider.SecretProvider}.
 */
public class SecretProviders {

  private static final Logger log = LoggerFactory.getLogger(SecretProviders.class);

  /**
   * Create a provider. Attempts to load it from a plugin if path is not null or using the app
   * class loader otherwise. If no provider plugin was found, returns a nop provider.
   */
  public static SecretProvider createSecretProvider(final Path path, final String address) {
    // Get a provider factory
    final SecretProviderFactory factory;
    if (path == null) {
      factory = createFactory();
    } else {
      factory = createFactory(path);
    }

    // Create the registrar
    if (address != null) {
      log.info("Creating secret provider with address: {}", address);
      return factory.create(address);
    } else {
      log.info("No address configured, not creating secret provider.");
      return new NopSecretProvider();
    }
  }

  /**
   * Get a provider factory from a plugin.
   */
  private static SecretProviderFactory createFactory(final Path path) {
    final SecretProviderFactory factory;
    final Path absolutePath = path.toAbsolutePath();
    try {
      factory = SecretProviderLoader.load(absolutePath);
      final String name = factory.getClass().getName();
      log.info("Loaded secret provider plugin: {} ({})", name, absolutePath);
    } catch (SecretProviderLoadingException e) {
      throw new RuntimeException("Unable to load secret provider plugin: " + absolutePath, e);
    }
    return factory;
  }

  /**
   * Get a provider factory from the application class loader.
   */
  private static SecretProviderFactory createFactory() {
    final SecretProviderFactory factory;
    final SecretProviderFactory installed;
    try {
      installed = SecretProviderLoader.load();
    } catch (SecretProviderLoadingException e) {
      throw new RuntimeException("Unable to load secret provider", e);
    }
    if (installed == null) {
      log.debug("No secret provider plugin configured");
      factory = new NopSecretProviderFactory();
    } else {
      factory = installed;
      final String name = factory.getClass().getName();
      log.info("Loaded installed secret provider: {}", name);
    }
    return factory;
  }
}
