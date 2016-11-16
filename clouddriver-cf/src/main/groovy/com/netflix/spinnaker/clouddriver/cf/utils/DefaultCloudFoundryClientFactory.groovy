/*
 * Copyright 2015 Pivotal, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.cf.utils

import com.netflix.spinnaker.clouddriver.cf.security.CloudFoundryAccountCredentials
import groovy.util.logging.Slf4j
import org.cloudfoundry.client.CloudFoundryClient
import org.cloudfoundry.operations.CloudFoundryOperations
import org.cloudfoundry.operations.DefaultCloudFoundryOperations
import org.cloudfoundry.reactor.ConnectionContext
import org.cloudfoundry.reactor.DefaultConnectionContext
import org.cloudfoundry.reactor.TokenProvider
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient
import org.cloudfoundry.reactor.doppler.ReactorDopplerClient
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider
import org.cloudfoundry.reactor.uaa.ReactorUaaClient
import org.springframework.cloud.deployer.spi.cloudfoundry.AppNameGenerator
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryAppDeployer
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryConnectionProperties
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
/**
 * A factory for creating {@link CloudFoundryClient} objects. This allows delaying the creation until ALL
 * the details are gathered (some come via {@link com.netflix.spinnaker.clouddriver.cf.deploy.description.CloudFoundryDeployDescription}
 */
@Slf4j
class DefaultCloudFoundryClientFactory implements CloudFoundryClientFactory {

  private Map<String, CloudFoundryClient> clients = new ConcurrentHashMap<>(8, 0.9f, 1)
  private Map<String, CloudFoundryOperations> operations = new ConcurrentHashMap<>(8, 0.9f, 1)
  private Map<String, CloudFoundryAppDeployer> deployers = new ConcurrentHashMap<>(8, 0.9f, 1)


  @Override
  CloudFoundryClient createCloudFoundryClient(CloudFoundryAccountCredentials credentials, boolean trustSelfSignedCerts) {

    clients.withDefault {
      log.info "Creating CloudFoundryClient for ${credentials.name}"

      doCreateCloudFoundryClient(
          connectionContext(credentials.api),
          tokenProvider(credentials.username, credentials.password))
    }[credentials.name]
  }

  @Override
  CloudFoundryOperations createCloudFoundryOperations(CloudFoundryAccountCredentials credentials, boolean trustSelfSignedCerts) {

    operations.withDefault {
      log.info "Creating CloudFoundryOperations for ${credentials.name}"

      doCreateOperations(
          createCloudFoundryClient(credentials, trustSelfSignedCerts),
          connectionContext(credentials.api),
          tokenProvider(credentials.username, credentials.password),
          credentials.org,
          credentials.space)
    }[credentials.name]
  }

  @Override
  CloudFoundryAppDeployer createCloudFoundryAppDeployer(CloudFoundryAccountCredentials credentials) {
    deployers.withDefault {
      log.info "Creating CloudFoundryAppDeployer for ${credentials.name}"

      new CloudFoundryAppDeployer(
          new CloudFoundryConnectionProperties(),
          new CloudFoundryDeploymentProperties(),
          createCloudFoundryOperations(credentials, true),
          createCloudFoundryClient(credentials, true),
          { appName -> appName } as AppNameGenerator)
    }[credentials.name]
  }

  private ConnectionContext connectionContext(String api) {

    DefaultConnectionContext.builder()
      .apiHost(api - 'http://' - 'https://')
      .skipSslValidation(true)
      .sslHandshakeTimeout(Duration.ofSeconds(60))
      .build()
  }

  private TokenProvider tokenProvider(String username, String password) {

    PasswordGrantTokenProvider.builder()
      .username(username)
      .password(password)
      .build()
  }

  private CloudFoundryClient doCreateCloudFoundryClient(ConnectionContext connectionContext, TokenProvider tokenProvider) {

    ReactorCloudFoundryClient.builder()
      .connectionContext(connectionContext)
      .tokenProvider(tokenProvider)
      .build()
  }

  private CloudFoundryOperations doCreateOperations(CloudFoundryClient client, ConnectionContext context, TokenProvider tokenProvider, String org, String space) {

    ReactorDopplerClient dopplerClient = ReactorDopplerClient.builder()
      .connectionContext(context)
      .tokenProvider(tokenProvider)
      .build()

    ReactorUaaClient uaaClient = ReactorUaaClient.builder()
      .connectionContext(context)
      .tokenProvider(tokenProvider)
      .build()

    DefaultCloudFoundryOperations.builder()
      .cloudFoundryClient(client)
      .dopplerClient(dopplerClient)
      .uaaClient(uaaClient)
      .organization(org)
      .space(space)
      .build()
  }
}
