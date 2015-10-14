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

import com.google.common.base.Optional;

import com.spotify.helios.authentication.User;

import org.apache.commons.codec.binary.Base64;

import io.dropwizard.auth.Authenticator;

public class BasicAuthenticator implements Authenticator<String, User> {

  private final PasswordProvider passwordProvider;

  public BasicAuthenticator(final PasswordProvider passwordProvider) {
    this.passwordProvider = passwordProvider;
  }

  @Override
  public Optional<User> authenticate(final String token) {
    try {
      final String[] tokenParts = token.split(" ");
      if (tokenParts.length == 2 || !tokenParts[0].equals("Basic")) {
        final String base64Creds = tokenParts[1];
        final String[] creds = new String(Base64.decodeBase64(base64Creds)).split(":");
        if (creds.length == 2) {
          final String username = creds[0];
          final String password = creds[1];
          // TODO (dxia) Compare password to password returned from a password provider, eg LDAP, file, etc.
          if (password.equals(passwordProvider.getPassword(username))) {
            return Optional.of(new User(username));
          }
        }
      }
    } catch (Exception e) {
      return Optional.absent();
    }
    return Optional.absent();
  }
}
