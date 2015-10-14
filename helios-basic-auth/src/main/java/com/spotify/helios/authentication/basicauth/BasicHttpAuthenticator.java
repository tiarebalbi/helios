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

import com.spotify.helios.authentication.AuthHeader;
import com.spotify.helios.authentication.HeliosAuthException;
import com.spotify.helios.authentication.HttpAuthenticator;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

public class BasicHttpAuthenticator implements HttpAuthenticator {

  @Override
  public String getSchemeName() {
    return "basic";
  }

  @Override
  public boolean verifyToken(final String username, final String accessToken) {
    // TODO (mbrown): implement
    return false;
  }

  @Override
  public String getHttpAuthHeaderKey() {
    // TODO (dxia) remove this method?
    return null;
  }

  @Override
  public AuthHeader parseHttpAuthHeaderValue(final String header) throws HeliosAuthException {
    throw new NotImplementedException();
  }

  @Override
  public String createChallenge(final String request) throws HeliosAuthException {
    throw new NotImplementedException();
  }

  @Override
  public String createToken(final String response) throws HeliosAuthException {
    throw new NotImplementedException();
  }

  @Override
  public String badAuthHeaderMsg() {
    return "Basic access authorization headers must be of the form "
           + "'Basic <base64-encoded username:password>";
  }
}
