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

package com.netflix.spinnaker.clouddriver.cf.deploy.handlers

import com.netflix.frigga.NameBuilder
import com.netflix.frigga.Names
import com.netflix.spinnaker.clouddriver.cf.config.CloudFoundryConstants
import com.netflix.spinnaker.clouddriver.cf.deploy.description.CloudFoundryDeployDescription
import com.netflix.spinnaker.clouddriver.cf.utils.CloudFoundryClientFactory
import com.netflix.spinnaker.clouddriver.cf.utils.RestTemplateFactory
import com.netflix.spinnaker.clouddriver.cf.utils.S3ServiceFactory
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.deploy.DeployDescription
import com.netflix.spinnaker.clouddriver.deploy.DeployHandler
import com.netflix.spinnaker.clouddriver.deploy.DeploymentResult
import com.netflix.spinnaker.clouddriver.helpers.OperationPoller
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.util.AsciiString
import org.cloudfoundry.operations.CloudFoundryOperations
import org.cloudfoundry.operations.routes.MapRouteRequest
import org.cloudfoundry.operations.spaces.GetSpaceRequest
import org.cloudfoundry.operations.spaces.SpaceDetail
import org.cloudfoundry.util.tuple.Function2
import org.jets3t.service.S3Service
import org.jets3t.service.S3ServiceException
import org.jets3t.service.model.S3Object
import org.jets3t.service.security.AWSCredentials
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cloud.deployer.spi.app.AppDeployer
import org.springframework.cloud.deployer.spi.app.AppStatus
import org.springframework.cloud.deployer.spi.app.DeploymentState
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryDeploymentProperties
import org.springframework.cloud.deployer.spi.core.AppDefinition
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest
import org.springframework.core.io.InputStreamResource
import org.springframework.core.io.PathResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.util.StreamUtils
import org.springframework.util.StringUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.function.Tuples

import java.nio.file.Files
import java.time.Duration
import java.util.function.Function

import static org.cloudfoundry.util.tuple.TupleUtils.function

class CloudFoundryDeployHandler2 implements DeployHandler<CloudFoundryDeployDescription> {

	private static final String BASE_PHASE = "DEPLOY"

	CloudFoundryClientFactory clientFactory

	RestTemplateFactory restTemplateFactory

	S3ServiceFactory s3ServiceFactory

	TaskRepository taskRepository

	@Autowired
	@Qualifier('cloudFoundryOperationPoller')
	OperationPoller operationPoller

	@Override
	DeploymentResult handle(CloudFoundryDeployDescription description, List priorOutputs) {

		def task = taskRepository.create(BASE_PHASE, "Initializing handler...")
		TaskRepository.threadLocalTask.set(task)

		def operations = clientFactory.createCloudFoundryOperations(description.credentials, true)

		def nameBuilder = new NameBuilder() {
			@Override
			public String combineAppStackDetail(String appName, String stack, String detail) {
				return super.combineAppStackDetail(appName, stack, detail)
			}
		}

		def clusterName = nameBuilder.combineAppStackDetail(description.application, description.stack, description.freeFormDetails)
		def nextSequence = getNextSequence(clusterName, operations, task).block(Duration.ofSeconds(30))
		task.updateStatus BASE_PHASE, "Found next sequence ${nextSequence}."

		description.serverGroupName = "${clusterName}-v${nextSequence}".toString()

		def properties = (description?.envs?.collectEntries { [it.key, it.value] } ?: [:])

		if (isJenkinsTrigger(description)) {
			properties[CloudFoundryConstants.JENKINS_HOST] = description.trigger.buildInfo.url
			properties[CloudFoundryConstants.JENKINS_NAME] = description.trigger.job
			properties[CloudFoundryConstants.JENKINS_BUILD] = description.trigger.buildNumber
			properties[CloudFoundryConstants.COMMIT_HASH] = description.trigger.buildInfo.scm[0].sha1
			properties[CloudFoundryConstants.COMMIT_BRANCH] = description.trigger.buildInfo.scm[0].branch
		}

		properties[CloudFoundryConstants.PACKAGE] = description.artifact
		properties[CloudFoundryConstants.LOAD_BALANCERS] = description.loadBalancers

		properties[CloudFoundryConstants.REPOSITORY] = description.repository
		properties[CloudFoundryConstants.ARTIFACT] = description.artifact
		properties[CloudFoundryConstants.ACCOUNT] = description.credentialAccount

		def artifactToDeploy
		if (description.repository.startsWith('http')) {
			// TODO: Replace with downloadJarFileFromWebWithReactor, and test against live candidate
			artifactToDeploy = downloadJarFileFromWeb(description, task)
		} else if (description.repository.startsWith('s3')) {
			artifactToDeploy = downloadJarFileFromS3(description, task)
		}  else {
			throw new RuntimeException("Repository '${description.repository}' is not a recognized protocol.")
		}

		def deploymentProperties = [
				(CloudFoundryDeploymentProperties.USE_SPRING_APPLICATION_JSON_KEY) : "false",
				(CloudFoundryDeploymentProperties.SERVICES_PROPERTY_KEY) : StringUtils.collectionToCommaDelimitedString(description.services),
				(CloudFoundryDeploymentProperties.BUILDPACK_PROPERTY_KEY) : description.buildpackUrl,
				(CloudFoundryDeploymentProperties.DISK_PROPERTY_KEY) : description.disk.toString(),
				(CloudFoundryDeploymentProperties.MEMORY_PROPERTY_KEY) : description.memory.toString(),
				(AppDeployer.COUNT_PROPERTY_KEY)												: "${description?.targetSize ?: 1}".toString()
		]

		def appDeployer = clientFactory.createCloudFoundryAppDeployer(description.credentials)

		task.updateStatus BASE_PHASE, "Deploying ${description.serverGroupName}."

		def applicationId = appDeployer.deploy(new AppDeploymentRequest(
			new AppDefinition(description.serverGroupName, properties),
			artifactToDeploy,
			deploymentProperties
		))

		// TODO: Map loadBalancer routes

		task.updateStatus BASE_PHASE, "Deployment launched. Monitoring progress of ${description.serverGroupName}"

		operationPoller.waitForOperation(
				{ appDeployer.status(applicationId) },
				{ AppStatus appStatus -> appStatus.state == DeploymentState.deployed },
				null, task, description.serverGroupName, BASE_PHASE)

		operations.spaces()
			.get(GetSpaceRequest.builder()
				.name(description.credentials.space)
				.build())
			.flatMap({ spaceDetail ->
				Flux.fromIterable(StringUtils.commaDelimitedListToSet(description.loadBalancers) + description.serverGroupName)
					.map({loadBalancerHost -> Tuples.of(loadBalancerHost, spaceDetail)})
				})
			.concatMap(function({ String loadBalancerHost, SpaceDetail spaceDetail ->
				operations.routes()
					.map(MapRouteRequest.builder()
						.applicationName(description.serverGroupName)
						.host(loadBalancerHost)
						.domain(spaceDetail.domains[0])
						.build())} as Function2))
			.collectList()
			.block(Duration.ofSeconds(60L))

		def deploymentResult = new DeploymentResult()
		deploymentResult.serverGroupNames << "${description.credentials.org}:${description.serverGroupName}".toString()
		deploymentResult.serverGroupNameByRegion[description.credentials.org] = description.serverGroupName
		deploymentResult.messages = task.history.collect { "${it.phase} : ${it.status}".toString() }

		deploymentResult
	}

	@Override
	boolean handles(DeployDescription description) {
		description instanceof CloudFoundryDeployDescription
	}

	/**
	 * Scan through all the apps in this space, and find the maximum one with a matching cluster name
	 *
	 * @param clusterName
	 * @param project
	 * @param region
	 * @param client
	 * @return
	 */
	private static Mono<Integer> getNextSequence(String clusterName, CloudFoundryOperations operations, Task task) {

		operations.applications()
			.list()
			.reduce(-1, { accum, app ->
				def names = Names.parseName(app.name)

				if (names.cluster == clusterName) {
					return Math.max(accum, names.sequence)
				} else {
					return accum
				}
			})
			.map({maxSequenceNumber -> String.format("%03d", (++maxSequenceNumber) % 1000)})
			.otherwise({ it ->
				task.updateStatus BASE_PHASE, "Problem -> ${it}"
			})
	}

	/**
	 * Discern if this is a Jenkins trigger
	 *
	 * @param description
	 * @return
	 */
	private boolean isJenkinsTrigger(CloudFoundryDeployDescription description) {
		description?.trigger?.job && description?.trigger?.buildNumber
	}

	private Resource downloadJarFileFromWebWithReactor(CloudFoundryDeployDescription description) {
		def authorization = "Basic " + Base64.getEncoder().encodeToString(new AsciiString(description.username).concat(":").concat(description.password).toByteArray())

		def connectionContext = DefaultConnectionContext.builder().build()

		return connectionContext.httpClient
				.get(description.repository + description.artifact, { outbound -> outbound
					.addHeader(HttpHeaderNames.AUTHORIZATION, authorization)
					.sendHeaders()})
				.then({ it.receive().aggregate().toInputStream() } as Function)
				.map({ new InputStreamResource(it) })
				.block();
	}

	private Resource downloadJarFileFromWeb(CloudFoundryDeployDescription description, Task task) {
		HttpHeaders requestHeaders = new HttpHeaders()

		if (description.username && description.password) {
			requestHeaders.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(new AsciiString(description.username).concat(":").concat(description.password).toByteArray()))
		}

		def requestEntity = new HttpEntity<>(requestHeaders)

		def restTemplate = this.restTemplateFactory.createRestTemplate()

		long contentLength = -1
		ResponseEntity<byte[]> responseBytes

		while (contentLength == -1 || contentLength != responseBytes?.headers?.getContentLength()) {
			if (contentLength > -1) {
				task.updateStatus BASE_PHASE, "Downloaded ${contentLength} bytes, but ${responseBytes.headers.getContentLength()} expected! Retry..."
			}
			def basePath = description.repository + (description.repository.endsWith('/') ? '' : '/')
			responseBytes = restTemplate.exchange("${basePath}${description.artifact}".toString(), HttpMethod.GET, requestEntity, byte[])
			contentLength = responseBytes != null ? responseBytes.getBody().length : 0;
		}

		task.updateStatus BASE_PHASE, "Successfully downloaded ${contentLength} bytes"

		def path = Files.createTempFile(description.artifact, "")
		Files.write(path, responseBytes.body)

		new PathResource(path)
	}

	private Resource downloadJarFileFromS3(CloudFoundryDeployDescription description, Task task) {
		AWSCredentials awsCredentials = null

		if (description.username && description.password) {
			awsCredentials = new AWSCredentials(description.username, description.password, description.credentials.name)
		}

		S3Service s3 = s3ServiceFactory.createS3Service(awsCredentials)

		def baseBucket = description.repository + (description.repository.endsWith('/') ? '' : '/') - 's3://'
		baseBucket = baseBucket.getAt(0..baseBucket.length()-2)

		task.updateStatus BASE_PHASE, "Downloading ${description.artifact} from ${baseBucket}..."

		S3Object object
		try {
			object = s3.getObject(baseBucket, description.artifact)
		} catch (S3ServiceException e) {
			task.updateStatus BASE_PHASE, "Failed to download ${description.artifact} from ${baseBucket} => ${e.message}"
			throw new RuntimeException(e.message)
		}
		task.updateStatus BASE_PHASE, "Found ${description.artifact} on S3"

		ByteArrayOutputStream downloadedBits = new ByteArrayOutputStream()
		StreamUtils.copy(object.dataInputStream, downloadedBits)

		task.updateStatus BASE_PHASE, "Successfully downloaded ${object.contentLength} bytes"

		def path = Files.createTempFile(description.artifact, "")
		Files.write(path, downloadedBits.toByteArray())

		new PathResource(path)
	}

}
