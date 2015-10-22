/*
 * Copyright (c) 2015 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import com.spotify.crtauth.CrtAuthClient;
import com.spotify.helios.client.HeliosRequest;
import com.spotify.helios.client.RequestDispatcher;
import com.spotify.helios.client.Response;

import java.net.URI;
import java.net.URISyntaxException;

public class CrtAuthProvider implements AuthProvider {

  private final RequestDispatcher dispatcher;
  private final String username;
  private volatile String authToken;
  private final Object lock = new Object();

  public CrtAuthProvider(final RequestDispatcher dispatcher, final String username) {
    this.dispatcher = dispatcher;
    this.username = username;
  }

  @Override
  public String currentAuthorizationHeader() {
    if (authToken == null) {
    }

    return authToken;
  }

  @Override
  public ListenableFuture<String> renewAuthorizationHeader(String authHeader) {
    final String authRequest = CrtAuthClient.createRequest(username);
    try {
      final URI uri = new URI("https://helios/_auth");
      final HeliosRequest request = HeliosRequest.builder()
          .method("GET")
          .uri(uri)
          .appendHeader("X-CHAP", "request:" + authRequest)
          .build();

      final ListenableFuture<Response> response = dispatcher.request(request);

      Futures.transform(response, new AsyncFunction<Response, Response>() {
        @Override
        public ListenableFuture<Response> apply(Response response) throws Exception {
          final String challengeHeader = response.header("X-CHAP");
          final int i = challengeHeader.indexOf(':');
          if (i == -1) {
            // Fail
          }
          final String substring = challengeHeader.substring(i + 1);
          CrtAuthClient.createResponse(challenge);

        }
      });

    } catch (URISyntaxException e) {
      // This should never happen
      throw Throwables.propagate(e);
    }
  }
}
