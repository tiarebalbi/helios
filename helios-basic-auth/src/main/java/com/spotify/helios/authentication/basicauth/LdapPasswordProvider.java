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

import com.google.common.base.Preconditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;

import java.util.List;

import javax.naming.NamingException;
import javax.naming.directory.Attributes;

import static org.springframework.ldap.query.LdapQueryBuilder.query;

/**
 * Returns the password for a specified username by looking it up in LDAP.
 */
public class LdapPasswordProvider implements PasswordProvider {

  private static final Logger log = LoggerFactory.getLogger(LdapPasswordProvider.class);

  private final LdapTemplate ldapTemplate;
  private final String baseSearchPath;
  private final String passwordAttr;

  public LdapPasswordProvider(final LdapTemplate ldapTemplate,
                              final String baseSearchPath,
                              final String passwordAttr) {
    this.ldapTemplate = Preconditions.checkNotNull(ldapTemplate);
    this.baseSearchPath = Preconditions.checkNotNull(baseSearchPath);
    this.passwordAttr = Preconditions.checkNotNull(passwordAttr);
  }

  public String getPassword(final String username) {
    final List<String> result = ldapTemplate.search(
        query()
            .base(baseSearchPath)
            .where("uid").is(username),
        new AttributesMapper<String>() {
          @Override
          public String mapFromAttributes(final Attributes attributes) throws NamingException {
            log.debug("got ldap stuff for uid {}", username);
            return attributes.get(passwordAttr).toString();
          }
        });
    return result.get(0);
  }
}
