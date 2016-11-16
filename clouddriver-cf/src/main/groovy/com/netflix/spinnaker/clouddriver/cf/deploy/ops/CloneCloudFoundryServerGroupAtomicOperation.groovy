/*
 * Copyright 2016 Pivotal
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

package com.netflix.spinnaker.clouddriver.cf.deploy.ops

import com.netflix.frigga.Names
import com.netflix.spinnaker.clouddriver.cf.config.CloudFoundryConstants
import com.netflix.spinnaker.clouddriver.cf.deploy.CloudFoundryServerGroupNameResolver
import com.netflix.spinnaker.clouddriver.cf.deploy.description.CloudFoundryDeployDescription
import com.netflix.spinnaker.clouddriver.cf.deploy.handlers.CloudFoundryDeployHandler2
import com.netflix.spinnaker.clouddriver.cf.security.CloudFoundryAccountCredentials
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.deploy.DeploymentResult
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation
import com.netflix.spinnaker.clouddriver.security.AccountCredentialsProvider
import org.cloudfoundry.client.CloudFoundryClient
import org.cloudfoundry.operations.CloudFoundryOperations
import org.cloudfoundry.operations.applications.ApplicationDetail
import org.cloudfoundry.operations.applications.ApplicationEnvironments
import org.cloudfoundry.operations.applications.GetApplicationEnvironmentsRequest
import org.cloudfoundry.operations.applications.GetApplicationRequest
import org.cloudfoundry.operations.services.ServiceInstance
import org.springframework.beans.factory.annotation.Autowired

import java.time.Duration

import static com.netflix.spinnaker.clouddriver.cf.utils.CloudFoundryJavaClientUtils.findServicesByServerGroupName

class CloneCloudFoundryServerGroupAtomicOperation implements AtomicOperation<DeploymentResult> {

	private static final String BASE_PHASE = "CLONE_SERVER_GROUP"

	@Autowired
	CloudFoundryDeployHandler2 deployHandler

	@Autowired
	AccountCredentialsProvider accountCredentialsProvider

	final CloudFoundryDeployDescription description

	private static Task getTask() {
		TaskRepository.threadLocalTask.get()
	}

	CloneCloudFoundryServerGroupAtomicOperation(CloudFoundryDeployDescription description) {
		this.description = description
	}

	@Override
	DeploymentResult operate(List priorOutputs) {

		CloudFoundryDeployDescription newDescription = cloneAndOverrideDescription()

		def serverGroupNameResolver = new CloudFoundryServerGroupNameResolver(description.credentials, deployHandler.clientFactory)
		def clusterName = serverGroupNameResolver.combineAppStackDetail(
				newDescription.application,
				newDescription.stack,
				newDescription.freeFormDetails)

		task.updateStatus BASE_PHASE, "Initializing copy of server group for cluster $clusterName in ${newDescription.space}..."

		task.updateStatus BASE_PHASE, "About to issue ${newDescription}"

		def result = deployHandler.handle(newDescription, priorOutputs)
		def newServerGroupName = getServerGroupName(result?.serverGroupNames?.getAt(0))

		task.updateStatus BASE_PHASE, "Finished copying server group for cluster $clusterName. " +
				"New server group = $newServerGroupName in ${newDescription.space}."


		result
	}

	private CloudFoundryDeployDescription cloneAndOverrideDescription() {
		CloudFoundryDeployDescription newDescription = description.clone()

		task.updateStatus BASE_PHASE, "Initializing copy of server group $description.source.serverGroupName..."

		CloudFoundryClient client = deployHandler.clientFactory.createCloudFoundryClient(description.credentials, true)

		CloudFoundryOperations operations = deployHandler.clientFactory.createCloudFoundryOperations(description.credentials, true)

		ApplicationDetail ancestor = operations.applications()
			.get(GetApplicationRequest.builder()
				.name(description.source.serverGroupName)
				.build())
			.block(Duration.ofSeconds(30))

		ApplicationEnvironments ancestorEnv = operations.applications()
			.getEnvironments(GetApplicationEnvironmentsRequest.builder()
				.name(description.source.serverGroupName)
				.build())
			.block(Duration.ofSeconds(30))

		List<ServiceInstance> ancestorServices = findServicesByServerGroupName(operations, client, description.source.serverGroupName)
			.collectList()
			.block(Duration.ofSeconds(60))


		def ancestorNames = Names.parseName(description.source.serverGroupName)

		newDescription.application = description.application ?: ancestorNames.app
		newDescription.stack = description.stack ?: ancestorNames.stack
		newDescription.freeFormDetails = description.freeFormDetails ?: ancestorNames.detail

		newDescription.space = description?.space ?: description.credentials.space

		newDescription.repository = newDescription?.repository ?: ancestorEnv.userProvided[CloudFoundryConstants.REPOSITORY]
		newDescription.artifact = newDescription?.artifact ?: ancestorEnv.userProvided[CloudFoundryConstants.ARTIFACT]

		def account = (CloudFoundryAccountCredentials) accountCredentialsProvider.getCredentials(newDescription.credentialAccount)

		newDescription.username = description?.username ?: account.artifactUsername
		newDescription.password = description?.password ?: account.artifactPassword
		newDescription.targetSize = description?.targetSize ?: ancestor.instances

		newDescription.disk = description?.disk ?: ancestor.diskQuota
		newDescription.memory = description?.memory ?: ancestor.memoryLimit

		// Do NOT pass along SPINNAKER metadata variables. Those values will be populated by the deployment handler.
		newDescription.envs = newDescription?.envs ?: ancestorEnv.userProvided.findAll { entry -> !entry.key.startsWith('SPINNAKER_') }

		def ancestorLoadBalancers = (description?.loadBalancers ?:
				ancestorEnv.userProvided[CloudFoundryConstants.LOAD_BALANCERS]).split(',')

		def newLoadBalancers = ancestorLoadBalancers.findAll { String lb ->
			!lb.startsWith(description.source.serverGroupName)
		}
		newDescription.loadBalancers = newLoadBalancers.join(',')

		newDescription.services = newDescription?.services ?: ancestorServices.collect { it.name }

		newDescription
	}

	private static String getServerGroupName(String regionPlusServerGroupName) {
		if (!regionPlusServerGroupName) {
			return 'Unknown'
		}

		def nameParts = regionPlusServerGroupName.split(":")

		return nameParts[nameParts.length - 1]
	}
}
