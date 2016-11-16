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
import org.cloudfoundry.client.CloudFoundryClient
import org.cloudfoundry.operations.CloudFoundryOperations
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryAppDeployer
/**
 * Factory interface for creating Cloud Foundry clients. Makes it possible to delay client
 * creation until ALL details are gathered.
 */
interface CloudFoundryClientFactory {

  CloudFoundryClient createCloudFoundryClient(CloudFoundryAccountCredentials credentials,
                                              boolean trustSelfSignedCerts)

  CloudFoundryOperations createCloudFoundryOperations(CloudFoundryAccountCredentials credentials,
                                                      boolean trustSelfSignedCerts)

  CloudFoundryAppDeployer createCloudFoundryAppDeployer(CloudFoundryAccountCredentials credentials)

}
