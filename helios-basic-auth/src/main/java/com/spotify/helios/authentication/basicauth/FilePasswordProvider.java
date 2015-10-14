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

import com.google.common.collect.ImmutableMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;

public class FilePasswordProvider implements PasswordProvider {

  private static final Logger log = LoggerFactory.getLogger(LdapPasswordProvider.class);

  private final Map<String, String> passwords;

  public FilePasswordProvider(final String filePath) throws IOException {
    final ImmutableMap.Builder<String, String> passwords = ImmutableMap.builder();
    try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
      String line;
      while ((line = br.readLine()) != null) {
        final String[] creds = line.split(" ");
        if (creds.length == 2) {
          passwords.put(creds[0], creds[1]);
        } else {
          log.warn("Got a line in password file {} that isn't formatted like so: "
                   + "'<username> <password>'. Skipping this line.");
        }
      }
    }
    this.passwords = passwords.build();
  }

  @Override
  public String getPassword(final String username) {
    return passwords.get(username);
  }
}
