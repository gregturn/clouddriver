/*
 * Copyright 2016 the original author or authors.
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
package com.netflix.spinnaker.clouddriver.cf.utils;

import com.netflix.spinnaker.clouddriver.cf.config.CloudFoundryConstants;
import com.netflix.spinnaker.clouddriver.cf.security.CloudFoundryAccountCredentials;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.routes.Route;
import org.cloudfoundry.client.v2.servicebindings.ListServiceBindingsRequest;
import org.cloudfoundry.client.v2.servicebindings.ServiceBindingEntity;
import org.cloudfoundry.client.v2.servicebindings.ServiceBindingResource;
import org.cloudfoundry.client.v2.serviceinstances.GetServiceInstanceRequest;
import org.cloudfoundry.client.v2.serviceinstances.GetServiceInstanceResponse;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.organizations.OrganizationDetail;
import org.cloudfoundry.operations.organizations.OrganizationInfoRequest;
import org.cloudfoundry.operations.services.ServiceInstance;
import org.cloudfoundry.operations.services.ServiceInstanceType;
import org.cloudfoundry.operations.spaces.GetSpaceRequest;
import org.cloudfoundry.operations.spaces.SpaceDetail;
import org.cloudfoundry.util.PaginationUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.cloudfoundry.util.tuple.TupleUtils.function;

/**
 * Collection of cf-java-client flows written in pure Java to support rest of clouddriver-cf.
 */
public class CloudFoundryJavaClientUtils {

	/**
	 * Look up {@link OrganizationDetail} based on organization's name.
	 *
	 * @param operations
	 * @param orgName
	 * @return
	 */
	public static Mono<OrganizationDetail> requestOrg(CloudFoundryOperations operations, String orgName) {

		return operations.organizations()
			.get(OrganizationInfoRequest.builder()
				.name(orgName)
				.build());
	}

	/**
	 * Look up {@link SpaceDetail} based on the space's name.
	 *
	 * @param operations
	 * @param spaceName
	 * @return
	 */
	public static Mono<SpaceDetail> requestSpace(CloudFoundryOperations operations, String spaceName) {

		return operations.spaces()
			.get(GetSpaceRequest.builder()
				.name(spaceName)
				.build());
	}

	/**
	 * Utility method to convert string value into {@link ServiceInstanceType} enum.
	 *
	 * TODO: Replace with https://github.com/cloudfoundry/cf-java-client/commit/1df2f83bc563e4a13047c806b211bb703e5c2813
	 */
	public static ServiceInstanceType serviceInstanceTypeFrom(String s) {

		switch (s.toLowerCase()) {
			case "managed_service_instance":
				return ServiceInstanceType.MANAGED;
			case "user_provided_service_instance":
				return ServiceInstanceType.USER_PROVIDED;
			default:
				throw new IllegalArgumentException(String.format("Unknown service instance type: %s", s));
		}
	}

	/**
	 * Translate server group name into {@link ApplicationDetail}. Handy for V2 APIs through {@literal getId()}.
	 *
	 * @param operations
	 * @param appName
	 * @return
	 */
	public static Mono<ApplicationDetail> requestApplicationDetail(CloudFoundryOperations operations, String appName) {

		return operations.applications()
			.get(GetApplicationRequest.builder()
				.name(appName)
				.build());
	}

	/**
	 * Find {@link ServiceInstance}s for a given server group name.
	 *
	 * @param operations
	 * @param client
	 * @param serverGroupName
	 * @return
	 */
	public static Flux<ServiceInstance> findServicesByServerGroupName(CloudFoundryOperations operations,
																	  CloudFoundryClient client,
																	  String serverGroupName) {

		return requestApplicationDetail(operations, serverGroupName)
			.flatMap(application -> findServicesByApplication(operations, client, application));
	}

	/**
	 * Using an application's LOAD_BALANCER environment variable, find all active routes.
	 *
	 * @param account
	 * @param environments
	 * @param routes
	 * @return
	 */
	public static Flux<Map<String, Object>> findLoadBalancers(CloudFoundryAccountCredentials account,
															  Map<String, Object> environments,
															  List<Route> routes) {

//		Hooks.onOperator(ops -> ops.operatorStacktrace());
		List<String> declaredLoadBalancers = Arrays.asList(environments.getOrDefault(CloudFoundryConstants.getLOAD_BALANCERS(), "").toString().split(","));

		return Flux.fromIterable(declaredLoadBalancers)
			.flatMap(loadBalancer -> Mono.just(routes.stream()
				.filter(route -> route.getHost().equals(loadBalancer))
				.findFirst()).and(Mono.just(loadBalancer)))
			.flatMap(function((optionalRoute, loadBalancer) ->
				optionalRoute
					.map(route -> {
						Map<String, Object> results = new HashMap<>();
						results.put("name", route.getHost());
						results.put("domain", route.getDomain().getName());
						results.put("region", account.getOrg());
						results.put("account", account);
						return Mono.just(results);
					})
					.orElseGet(() -> {
						Map<String, Object> results = new HashMap<>();
						results.put("name", loadBalancer);
						results.put("region", account.getOrg());
						results.put("account", account);
						return Mono.just(results);
					})));
	}

	/**
	 * Find all {@link ServiceInstance}s mapped to the collection of {@link ApplicationDetail}s.
	 *
	 * @param operations
	 * @param client
	 * @param applications
	 * @return
	 */
	public static Flux<ServiceInstance> findServicesByApplications(CloudFoundryOperations operations,
																   CloudFoundryClient client,
																   List<ApplicationDetail> applications) {

		return requestServiceBindingsByApplicationId(client, applicationDetailsToApplicationIds(applications))
			.map(ServiceBindingResource::getEntity)
			.map(ServiceBindingEntity::getServiceInstanceId)
			.flatMap(serviceInstanceId -> client.serviceInstances()
				.get(GetServiceInstanceRequest.builder()
					.serviceInstanceId(serviceInstanceId)
					.build()))
			.map(GetServiceInstanceResponse::getEntity)
			.flatMap(serviceInstanceEntity -> operations.services()
				.getInstance(org.cloudfoundry.operations.services.GetServiceInstanceRequest.builder()
					.name(serviceInstanceEntity.getName())
					.build()));
	}

	/**
	 * Find all {@link ServiceInstance}s related to a single {@link ApplicationDetail}.
	 *
	 * @param operations
	 * @param client
	 * @param application
	 * @return
	 */
	public static Flux<ServiceInstance> findServicesByApplication(CloudFoundryOperations operations,
																  CloudFoundryClient client,
																  ApplicationDetail application) {

		return findServicesByApplications(operations, client, Collections.singletonList(application));
	}

	/**
	 * Look up services bound to a list of application ids.
	 *
	 * @param client
	 * @param applicationIds
	 * @return {@link Flux} of {@link ServiceBindingResource}s.
	 */
	private static Flux<ServiceBindingResource> requestServiceBindingsByApplicationId(CloudFoundryClient client,
																					  List<String> applicationIds) {

		return PaginationUtils.requestClientV2Resources(page -> client.serviceBindingsV2()
			.list(ListServiceBindingsRequest.builder()
				.page(page)
				.applicationIds(applicationIds)
				.build()));
	}

	/**
	 * Convert a list of {@link ApplicationDetail}s to a list of application ids.
	 *
	 * @param applications
	 * @return
	 */
	private static List<String> applicationDetailsToApplicationIds(List<ApplicationDetail> applications) {

		return applications.stream()
			.map(ApplicationDetail::getId)
			.collect(Collectors.toList());
	}

}
