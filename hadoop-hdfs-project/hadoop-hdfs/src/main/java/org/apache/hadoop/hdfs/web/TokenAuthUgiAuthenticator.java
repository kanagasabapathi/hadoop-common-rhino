/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.web;

import java.io.IOException;

import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authentication.client.Authenticator;
import org.apache.hadoop.security.authentication.client.PseudoAuthenticator;
import org.apache.hadoop.security.tokenauth.web.TokenAuthAuthenticator;

public class TokenAuthUgiAuthenticator extends TokenAuthAuthenticator {
  
  public TokenAuthUgiAuthenticator() {
    super(SecurityUtil.getHASClient(null));
  }
  
  @Override
  protected Authenticator getFallBackAuthenticator() {
    return new PseudoAuthenticator() {
      @Override
      protected String getUserName() {
        try {
          return UserGroupInformation.getLoginUser().getUserName();
        } catch (IOException e) {
          throw new SecurityException("Failed to obtain current username", e);
        }
      }
    };
  }
}
