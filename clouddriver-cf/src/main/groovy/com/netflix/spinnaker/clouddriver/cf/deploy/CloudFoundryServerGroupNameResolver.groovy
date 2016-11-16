/*
 * Copyright 2016 Pivotal, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.cf.deploy

import com.netflix.frigga.Names
import com.netflix.spinnaker.clouddriver.cf.security.CloudFoundryAccountCredentials
import com.netflix.spinnaker.clouddriver.cf.utils.CloudFoundryClientFactory
import com.netflix.spinnaker.clouddriver.helpers.AbstractServerGroupNameResolver
import org.cloudfoundry.client.v2.applications.GetApplicationRequest
import org.cloudfoundry.util.tuple.Function2
import reactor.core.publisher.Mono
import reactor.util.function.Tuples

import java.text.DateFormat
import java.time.Duration

import static org.cloudfoundry.util.tuple.TupleUtils.function

class CloudFoundryServerGroupNameResolver extends AbstractServerGroupNameResolver {

	private static final String PHASE = "DEPLOY"

	private final CloudFoundryAccountCredentials credentials
	private final CloudFoundryClientFactory clientFactory

	CloudFoundryServerGroupNameResolver(CloudFoundryAccountCredentials credentials,
										CloudFoundryClientFactory clientFactory) {
		this.credentials = credentials
		this.clientFactory = clientFactory
	}

	@Override
	String getPhase() {
		PHASE
	}

	@Override
	String getRegion() {
		credentials.org
	}

	@Override
	List<AbstractServerGroupNameResolver.TakenSlot> getTakenSlots(String clusterName) {

		def operations = clientFactory.createCloudFoundryOperations(credentials, true)
		def client = clientFactory.createCloudFoundryClient(credentials, true)

		operations.applications()
			.list()
			.map({ app -> Tuples.of(Names.parseName(app.name), app)})
			.filter(function({ parsedName, app ->
				parsedName.cluster == clusterName
			} as Function2))
			.map(function({ parsedName, app -> client.applicationsV2()
				.get(GetApplicationRequest.builder()
					.applicationId(app.id)
					.build())
				.and(Mono.just(parsedName))
			} as Function2))
			.map(function({ applicationResource, parsedName ->
				new AbstractServerGroupNameResolver.TakenSlot(
					serverGroupName: combineAppStackDetail(parsedName.app, parsedName.stack, parsedName.detail),
					sequence: parsedName.sequence,
					createdTime: DateFormat.dateTimeInstance.parse(applicationResource.metadata.updatedAt)
				)
			} as Function2))
			.collectList()
			.block(Duration.ofSeconds(60))
	}
}
