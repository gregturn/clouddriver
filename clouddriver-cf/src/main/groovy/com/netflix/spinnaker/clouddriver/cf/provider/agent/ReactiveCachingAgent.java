/*
 * Copyright 2017 the original author or authors.
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
package com.netflix.spinnaker.clouddriver.cf.provider.agent;

import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.cats.agent.AccountAware;
import com.netflix.spinnaker.cats.agent.AgentDataType;
import com.netflix.spinnaker.cats.agent.CacheResult;
import com.netflix.spinnaker.cats.agent.CachingAgent;
import com.netflix.spinnaker.cats.agent.DefaultCacheResult;
import com.netflix.spinnaker.cats.cache.CacheData;
import com.netflix.spinnaker.cats.cache.DefaultCacheData;
import com.netflix.spinnaker.cats.provider.ProviderCache;
import com.netflix.spinnaker.clouddriver.cache.OnDemandAgent;
import com.netflix.spinnaker.clouddriver.cache.OnDemandMetricsSupport;
import com.netflix.spinnaker.clouddriver.cf.CloudFoundryCloudProvider;
import com.netflix.spinnaker.clouddriver.cf.cache.Keys;
import com.netflix.spinnaker.clouddriver.cf.config.CloudFoundryConstants;
import com.netflix.spinnaker.clouddriver.cf.provider.CloudFoundryProvider;
import com.netflix.spinnaker.clouddriver.cf.security.CloudFoundryAccountCredentials;
import com.netflix.spinnaker.clouddriver.cf.utils.CloudFoundryClientFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.AbstractApplicationResource;
import org.cloudfoundry.client.v2.applications.ApplicationInstanceInfo;
import org.cloudfoundry.client.v2.applications.ApplicationInstancesRequest;
import org.cloudfoundry.client.v2.applications.ApplicationInstancesResponse;
import org.cloudfoundry.client.v2.applications.ApplicationResource;
import org.cloudfoundry.client.v2.applications.ApplicationStatisticsRequest;
import org.cloudfoundry.client.v2.applications.ApplicationStatisticsResponse;
import org.cloudfoundry.client.v2.applications.InstanceStatistics;
import org.cloudfoundry.client.v2.applications.Statistics;
import org.cloudfoundry.client.v2.applications.SummaryApplicationRequest;
import org.cloudfoundry.client.v2.applications.SummaryApplicationResponse;
import org.cloudfoundry.client.v2.applications.Usage;
import org.cloudfoundry.client.v2.routes.Route;
import org.cloudfoundry.client.v2.spaces.ListSpaceApplicationsRequest;
import org.cloudfoundry.operations.CloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationDetail;
import org.cloudfoundry.operations.applications.InstanceDetail;
import org.cloudfoundry.operations.organizations.OrganizationDetail;
import org.cloudfoundry.operations.services.ServiceInstance;
import org.cloudfoundry.operations.spaces.SpaceDetail;
import org.cloudfoundry.util.DateUtils;
import org.cloudfoundry.util.ExceptionUtils;
import org.cloudfoundry.util.OperationUtils;
import org.cloudfoundry.util.PaginationUtils;
import org.cloudfoundry.util.ResourceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.netflix.spinnaker.clouddriver.cf.utils.CloudFoundryJavaClientUtils.*;
import static org.cloudfoundry.util.tuple.TupleUtils.*;

/**
 * Reactor-based implementation of caching agent, used to interact with Cloud Foundry instances
 * via cf-java-client API.
 */
public class ReactiveCachingAgent implements CachingAgent, OnDemandAgent, AccountAware {

	private final static Logger log = LoggerFactory.getLogger(ReactiveCachingAgent.class);


	private static final int CF_APP_STOPPED_STATS_ERROR = 200003;

	private static final int CF_BUILDPACK_COMPILED_FAILED = 170004;

	private static final int CF_INSTANCES_ERROR = 220001;

	private static final int CF_STAGING_NOT_FINISHED = 170002;

	private static final int CF_STAGING_TIME_EXPIRED = 170007;


	private final CloudFoundryClientFactory cloudFoundryClientFactory;
	private final CloudFoundryAccountCredentials account;
	private final ObjectMapper objectMapper;

	private final OnDemandMetricsSupport metricsSupport;

	private static final Set<AgentDataType> TYPES = Collections.unmodifiableSet(new HashSet<AgentDataType>() {{
		add(AgentDataType.Authority.AUTHORITATIVE.forType(Keys.Namespace.SERVER_GROUPS.getNs()));
		add(AgentDataType.Authority.INFORMATIVE.forType(Keys.Namespace.CLUSTERS.getNs()));
		add(AgentDataType.Authority.INFORMATIVE.forType(Keys.Namespace.APPLICATIONS.getNs()));
		add(AgentDataType.Authority.AUTHORITATIVE.forType(Keys.Namespace.INSTANCES.getNs()));
		add(AgentDataType.Authority.AUTHORITATIVE.forType(Keys.Namespace.LOAD_BALANCERS.getNs()));
	}});

	public ReactiveCachingAgent(CloudFoundryClientFactory cloudFoundryClientFactory,
								CloudFoundryAccountCredentials account,
								ObjectMapper objectMapper,
								Registry registry) {

		this.cloudFoundryClientFactory = cloudFoundryClientFactory;
		this.account = account;
		this.objectMapper = objectMapper;
		this.metricsSupport = new OnDemandMetricsSupport(registry, this, CloudFoundryCloudProvider.getID() + ":" + OnDemandType.ServerGroup);
	}

	@Override
	public String getAgentType() {
		return this.account.getName() + "/" + this.getClass().getSimpleName();
	}

	@Override
	public String getOnDemandAgentType() {
		return this.getAgentType() + "-OnDemand";
	}

	@Override
	public String getAccountName() {
		return this.account.getName();
	}

	@Override
	public String getProviderName() {
		return CloudFoundryProvider.getPROVIDER_NAME();
	}

	@Override
	public OnDemandMetricsSupport getMetricsSupport() {
		return this.metricsSupport;
	}

	@Override
	public Collection<AgentDataType> getProvidedDataTypes() {
		return TYPES;
	}

	@Override
	public boolean handles(OnDemandType type, String cloudProvider) {
		return type == OnDemandType.ServerGroup && cloudProvider.equals(CloudFoundryCloudProvider.getID());
	}

	@Override
	public Collection<Map> pendingOnDemandRequests(ProviderCache providerCache) {

		Collection<String> keys = providerCache.getIdentifiers(Keys.Namespace.ON_DEMAND.getNs());

		return providerCache.getAll(Keys.Namespace.ON_DEMAND.getNs(), keys).stream()
			.map(cacheData -> new HashMap<String, Object>() {{
				put("id", cacheData.getId());
				put("details", Keys.parse(cacheData.getId()));
				put("processedCount", cacheData.getAttributes().getOrDefault("processedCount", 0));
				put("processedTime", cacheData.getAttributes().get("processedTime"));
			}})
			.collect(Collectors.toList());
	}

	@Override
	public CacheResult loadData(ProviderCache providerCache) {

		log.info("Describing items in " + getAgentType());

		long start = System.currentTimeMillis();

		CloudFoundryOperations operations = cloudFoundryClientFactory.createCloudFoundryOperations(account, true);
		CloudFoundryClient client = cloudFoundryClientFactory.createCloudFoundryClient(account, true);

//		Hooks.onOperator(ops -> ops.operatorStacktrace());

		return requestSpace(operations, account.getSpace())
			.map(space -> {
				log.debug("Loading " + space.getApplications());

				List<CacheData> evictableOnDemandCacheDatas = new ArrayList<>();
				List<CacheData> keepInOnDemand = new ArrayList<>();

				Collection<CacheData> cacheDatas = providerCache.getAll(Keys.Namespace.ON_DEMAND.getNs(), space.getApplications().stream()
					.map(app -> Keys.getServerGroupKey(app, account.getName(), account.getOrg()))
					.collect(Collectors.toList()));

				cacheDatas.forEach(cacheData -> {
					if ((long) cacheData.getAttributes().getOrDefault("cacheTime", 0) < start && (int) cacheData.getAttributes().getOrDefault("processedCount", 0) > 0) {
						evictableOnDemandCacheDatas.add(cacheData);
					} else {
						keepInOnDemand.add(cacheData);
					}
				});

				return Tuples.of(space, evictableOnDemandCacheDatas, keepInOnDemand);
			})
			.then(function((space, evictableOnDemandCacheDatas, keepInOnDemand) -> {
				Map<String, CacheData> stuffToConsider = keepInOnDemand.stream()
					.collect(Collectors.toMap(
						cacheData -> cacheData.getId(),
						cacheData -> cacheData));
				return buildCacheResult(
					operations,
					client,
					stuffToConsider,
					evictableOnDemandCacheDatas.stream()
						.map(cacheData -> cacheData.getId())
						.collect(Collectors.toList()),
					start,
					account,
					objectMapper,
					space.getApplications(),
					space);
			}))
			.map(results -> {
				results.getCacheResults().get(Keys.Namespace.ON_DEMAND.getNs()).forEach(cacheData -> {
					cacheData.getAttributes().put("processedTime", System.currentTimeMillis());
					cacheData.getAttributes().put("processedCount", (int) cacheData.getAttributes().getOrDefault("processedCount", 0) + 1);
				});
				return results;
			})
			.block(Duration.ofMinutes(2));
	}

	@Override
	public OnDemandResult handle(ProviderCache providerCache, Map<String, ? extends Object> data) {

		if (!data.containsKey("serverGroupName")) {
			return null;
		}
		if (!data.containsKey("account")) {
			return null;
		}
		if (!data.containsKey("region")) {
			return null;
		}

		if (!account.getName().equals(data.get("account").toString())) {
			return null;
		}

		if (!account.getOrg().equals(data.get("region").toString())) {
			return null;
		}

		String serverGroupName = data.get("serverGroupName").toString();

		CloudFoundryOperations operations = cloudFoundryClientFactory.createCloudFoundryOperations(account, true);
		CloudFoundryClient client = cloudFoundryClientFactory.createCloudFoundryClient(account, true);

//		Hooks.onOperator(ops -> ops.operatorStacktrace());

		return
			requestSpace(operations, account.getSpace())
				.then(space -> buildCacheResult(
					operations,
					client,
					new HashMap<>(),
					new ArrayList<>(),
					Long.MAX_VALUE,
					account,
					objectMapper,
					Collections.singletonList(serverGroupName),
					space))
				.map(cacheResult -> {
					if (cacheResult.getCacheResults().values().isEmpty()) {
						// Avoid writing an empty onDemand cache record (instead delete any that may have previously existed).
						providerCache.evictDeletedItems(Keys.Namespace.ON_DEMAND.getNs(), Collections.singletonList(Keys.getServerGroupKey(serverGroupName, account.getName(), account.getOrg())));
						return cacheResult;
					} else {
						DefaultCacheData cacheData = new DefaultCacheData(
							Keys.getServerGroupKey(serverGroupName, account.getName(), account.getOrg()),
							10 * 60,
							new HashMap<String, Object>() {{
								put("cacheTime", System.currentTimeMillis());

								String resultsAsJson;
								try {
									resultsAsJson = objectMapper.writeValueAsString(cacheResult.getCacheResults());
								} catch (JsonProcessingException e) {
									resultsAsJson = null;
								}

								put("cacheResults", resultsAsJson);
								put("processedCount", 0);
								put("processedTime", null);
							}},
							new HashMap<>());

						providerCache.putCacheData(Keys.Namespace.ON_DEMAND.getNs(), cacheData);
						return cacheResult;
					}
				})
				.map(cacheResult -> {
					if (cacheResult.getCacheResults().get(Keys.Namespace.APPLICATIONS.getNs()).size() > 0) {
						return Tuples.of(Collections.<String, Collection<String>>emptyMap(), cacheResult);
					} else {
						return Tuples.of(
							Collections.<String, Collection<String>>singletonMap(
								Keys.Namespace.SERVER_GROUPS.getNs(),
								Collections.singletonList(Keys.getServerGroupKey(serverGroupName, account.getName(), account.getOrg()))),
							cacheResult);
					}
				})
				.map(function((evictions, cacheResult) -> {
					log.info("onDemand cache refresh data (data: " + data + ", evictions: " + evictions);
					OnDemandResult result = new OnDemandResult();
					result.setSourceAgentType(getOnDemandAgentType());
					result.setEvictions(evictions);
					result.setCacheResult(cacheResult);
					return result;
				}))
				.block(Duration.ofMinutes(5));
	}

	private static Mono<DefaultCacheResult> buildCacheResult(CloudFoundryOperations operations,
															 CloudFoundryClient client,
															 Map<String, CacheData> onDemandKeep,
															 Collection<String> evictableOnDemandCacheDataIdentifiers,
															 Long start,
															 CloudFoundryAccountCredentials account,
															 ObjectMapper objectMapper,
															 List<String> theseServerGroups,
															 SpaceDetail space) {

		Map<String, CacheData> applications = ReactiveCachingAgentSupport.cache();
		Map<String, CacheData> clusters = ReactiveCachingAgentSupport.cache();
		Map<String, CacheData> serverGroups = ReactiveCachingAgentSupport.cache();
		Map<String, CacheData> instances = ReactiveCachingAgentSupport.cache();
		Map<String, CacheData> loadBalancers = ReactiveCachingAgentSupport.cache();

		log.info("Building cache result...");

		return requestOrg(operations, account.getOrg())
			.and(Mono.just(theseServerGroups))
			.flatMap(function((org, appNames) ->
				getApplicationDetails(client, space, appNames)
					.map(applicationDetail ->
						Tuples.of(org, applicationDetail.getT1(), applicationDetail.getT2().getEnvironmentJsons(), applicationDetail.getT2().getRoutes()))))
			.collectList()
			.flatMap(orgAppEnvsRoutes -> findServicesByApplications(operations, client, orgAppEnvsRoutes.stream()
				.map(function((org, app, envs, routes) -> app))
				.collect(Collectors.toList()))
				.collectList()
				.flatMap(serviceInstances -> Flux.fromIterable(orgAppEnvsRoutes)
					.map(function((org, app, envs, routes) -> Tuples.of(org, app, envs, routes, serviceInstances)))
				)
			)
			.flatMap(function((org, application, environments, routes, serviceInstances) ->
				cacheUpResults(application, applications, clusters, instances, loadBalancers, serverGroups, org, space, account, objectMapper, start, onDemandKeep, environments, serviceInstances, routes)
			))
			.collectList()
			.map(objects -> {
				HashMap<String, Collection<CacheData>> cacheResults = new HashMap<>();
				cacheResults.put(Keys.Namespace.APPLICATIONS.getNs(), applications.values());
				cacheResults.put(Keys.Namespace.CLUSTERS.getNs(), clusters.values());
				cacheResults.put(Keys.Namespace.SERVER_GROUPS.getNs(), serverGroups.values());
				cacheResults.put(Keys.Namespace.INSTANCES.getNs(), instances.values());
				cacheResults.put(Keys.Namespace.LOAD_BALANCERS.getNs(), loadBalancers.values());
				cacheResults.put(Keys.Namespace.ON_DEMAND.getNs(), onDemandKeep.values());

				HashMap<String, Collection<String>> evictions = new HashMap<>();
				evictions.put(Keys.Namespace.ON_DEMAND.getNs(), evictableOnDemandCacheDataIdentifiers);

				return new DefaultCacheResult(cacheResults, evictions);
			});
	}

	private static Mono<?> cacheUpResults(ApplicationDetail application,
										  Map<String, CacheData> applications,
										  Map<String, CacheData> clusters,
										  Map<String, CacheData> instances,
										  Map<String, CacheData> loadBalancers,
										  Map<String, CacheData> serverGroups,
										  OrganizationDetail org,
										  SpaceDetail space,
										  CloudFoundryAccountCredentials account,
										  ObjectMapper objectMapper,
										  long start,
										  Map<String, CacheData> onDemandKeep,
										  Map<String, Object> environments,
										  List<ServiceInstance> serviceInstances,
										  List<Route> routes) {

//		Hooks.onOperator(ops -> ops.operatorStacktrace());

		CacheData onDemandData = (onDemandKeep != null) ? onDemandKeep.get(Keys.getServerGroupKey(application.getName(), account.getName(), account.getOrg())) : null;

		if (onDemandData != null && ((Long) onDemandData.getAttributes().get("cacheTime")) >= start) {
			log.info("Using onDemand cache value (" + onDemandData.getId() + ")");
			Map<String, List<CacheData>> cacheResults;
			try {
				cacheResults = objectMapper.readValue(
					onDemandData.getAttributes().get("cacheResults").toString(),
					new TypeReference<Map<String, List<ReactiveCachingAgentSupport.MutableCacheData>>>() {});
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			ReactiveCachingAgentSupport.cache(cacheResults, Keys.Namespace.APPLICATIONS.getNs(), applications);
			ReactiveCachingAgentSupport.cache(cacheResults, Keys.Namespace.CLUSTERS.getNs(), clusters);
			ReactiveCachingAgentSupport.cache(cacheResults, Keys.Namespace.SERVER_GROUPS.getNs(), serverGroups);
			ReactiveCachingAgentSupport.cache(cacheResults, Keys.Namespace.INSTANCES.getNs(), instances);
			ReactiveCachingAgentSupport.cache(cacheResults, Keys.Namespace.LOAD_BALANCERS.getNs(), loadBalancers);
			return Mono.empty();
		} else {
			return cacheUpResultsImmediately(application, applications, clusters, instances, loadBalancers, serverGroups, org, space, account, environments, serviceInstances, routes);
		}
	}

	private static Mono<?> cacheUpResultsImmediately(ApplicationDetail application,
													 Map<String, CacheData> applications,
													 Map<String, CacheData> clusters,
													 Map<String, CacheData> instances,
													 Map<String, CacheData> loadBalancers,
													 Map<String, CacheData> serverGroups,
													 OrganizationDetail org,
													 SpaceDetail space,
													 CloudFoundryAccountCredentials account,
													 Map<String, Object> environments,
													 List<ServiceInstance> serviceInstances,
													 List<Route> routes) {

//		Hooks.onOperator(ops -> ops.operatorStacktrace());

		return findLoadBalancers(account, environments, routes).collectList()
			.and(Flux.fromIterable(serviceInstances)
				.filter(isServiceMappedToApplication(application)).collectList())
			.log("lbsAndServices")
			.map(function((lbs, svcs) -> {
				ReactiveCachingAgentSupport.CloudFoundryData data = new ReactiveCachingAgentSupport.CloudFoundryData(application, account, new HashSet<>(lbs), org, space);

				ReactiveCachingAgentSupport.cacheApplications(data, applications);
				ReactiveCachingAgentSupport.cacheCluster(data, clusters);
				ReactiveCachingAgentSupport.cacheInstances(data, instances, account);
				ReactiveCachingAgentSupport.cacheLoadBalancers(data, loadBalancers);
				ReactiveCachingAgentSupport.cacheServerGroup(data, serverGroups, svcs, environments, account);
				return Mono.empty();
			}));
	}

	private static Predicate<ServiceInstance> isServiceMappedToApplication(ApplicationDetail application) {
		return serviceInstance -> serviceInstance.getApplications().contains(application.getName());
	}

	private static Flux<Tuple2<ApplicationDetail, SummaryApplicationResponse>> getApplicationDetails(CloudFoundryClient client, SpaceDetail space, List<String> names) {

		return getApplications(client, names, space.getId())
			.flatMap(function(ReactiveCachingAgent::getAuxiliaryContent))
			.map(function(ReactiveCachingAgent::toApplicationDetail));
	}

	private static Flux<Tuple2<CloudFoundryClient, AbstractApplicationResource>> getApplications(CloudFoundryClient cloudFoundryClient, List<String> apps, String spaceId) {

		return requestApplications(cloudFoundryClient, apps, spaceId)
			.filter(applicationResource ->
				applicationResource.getEntity().getEnvironmentJsons() != null &&
				applicationResource.getEntity().getEnvironmentJsons().containsKey(CloudFoundryConstants.getLOAD_BALANCERS())
			)
			.map(resource -> Tuples.of(cloudFoundryClient, resource))
			.switchIfEmpty(t -> ExceptionUtils.illegalArgument("Applications %s do not exist", apps));
	}

	private static Mono<Tuple3<SummaryApplicationResponse, List<InstanceDetail>, List<String>>> getAuxiliaryContent(CloudFoundryClient cloudFoundryClient, AbstractApplicationResource applicationResource) {

		String applicationId = ResourceUtils.getId(applicationResource);

		return Mono
			.when(
				getApplicationStatistics(cloudFoundryClient, applicationId),
				requestApplicationSummary(cloudFoundryClient, applicationId),
				getApplicationInstances(cloudFoundryClient, applicationId)
			)
			.then(function((applicationStatisticsResponse, summaryApplicationResponse, applicationInstancesResponse) -> Mono.when(
				Mono.just(summaryApplicationResponse),
				toInstanceDetailList(applicationInstancesResponse, applicationStatisticsResponse),
				toUrls(summaryApplicationResponse.getRoutes())
			)));
	}

	private static Tuple2<ApplicationDetail, SummaryApplicationResponse> toApplicationDetail(SummaryApplicationResponse summaryApplicationResponse,
																							 List<InstanceDetail> instanceDetails,
																							 List<String> urls) {
		return Tuples.of(ApplicationDetail.builder()
			.diskQuota(summaryApplicationResponse.getDiskQuota())
			.id(summaryApplicationResponse.getId())
			.instanceDetails(instanceDetails)
			.instances(summaryApplicationResponse.getInstances())
			.lastUploaded(toDate(summaryApplicationResponse.getPackageUpdatedAt()))
			.memoryLimit(summaryApplicationResponse.getMemory())
			.name(summaryApplicationResponse.getName())
			.requestedState(summaryApplicationResponse.getState())
			.runningInstances(summaryApplicationResponse.getRunningInstances())
			.urls(urls)
			.stack("")
			.build(),
			summaryApplicationResponse);
	}

	private static Flux<AbstractApplicationResource> requestApplications(CloudFoundryClient client, List<String> applications, String spaceId) {
		return PaginationUtils
			.requestClientV2Resources(page -> client.spaces()
				.listApplications(ListSpaceApplicationsRequest.builder()
					.names(applications)
					.spaceId(spaceId)
					.page(page)
					.build()))
			.map(OperationUtils.<ApplicationResource, AbstractApplicationResource>cast());
	}


	private static Mono<ApplicationStatisticsResponse> getApplicationStatistics(CloudFoundryClient cloudFoundryClient, String applicationId) {
		return requestApplicationStatistics(cloudFoundryClient, applicationId)
			.otherwise(ExceptionUtils.statusCode(CF_APP_STOPPED_STATS_ERROR), t -> Mono.just(ApplicationStatisticsResponse.builder().build()));
	}

	private static Mono<SummaryApplicationResponse> requestApplicationSummary(CloudFoundryClient cloudFoundryClient, String applicationId) {
		return cloudFoundryClient.applicationsV2()
			.summary(SummaryApplicationRequest.builder()
				.applicationId(applicationId)
				.build());
	}

	private static Mono<ApplicationInstancesResponse> getApplicationInstances(CloudFoundryClient cloudFoundryClient, String applicationId) {
		return requestApplicationInstances(cloudFoundryClient, applicationId)
			.otherwise(ExceptionUtils.statusCode(CF_BUILDPACK_COMPILED_FAILED, CF_INSTANCES_ERROR, CF_STAGING_NOT_FINISHED, CF_STAGING_TIME_EXPIRED),
				t -> Mono.just(ApplicationInstancesResponse.builder().build()));
	}

	private static Mono<List<InstanceDetail>> toInstanceDetailList(ApplicationInstancesResponse instancesResponse, ApplicationStatisticsResponse statisticsResponse) {
		return Flux
			.fromIterable(instancesResponse.getInstances().entrySet())
			.map(entry -> toInstanceDetail(entry, statisticsResponse))
			.collectList();
	}

	private static Mono<List<String>> toUrls(List<Route> routes) {
		return Flux
			.fromIterable(routes)
			.map(ReactiveCachingAgent::toUrl)
			.collectList();
	}

	private static Mono<ApplicationStatisticsResponse> requestApplicationStatistics(CloudFoundryClient cloudFoundryClient, String applicationId) {
		return cloudFoundryClient.applicationsV2()
			.statistics(ApplicationStatisticsRequest.builder()
				.applicationId(applicationId)
				.build());
	}

	private static Mono<ApplicationInstancesResponse> requestApplicationInstances(CloudFoundryClient cloudFoundryClient, String applicationId) {
		return cloudFoundryClient.applicationsV2()
			.instances(ApplicationInstancesRequest.builder()
				.applicationId(applicationId)
				.build());
	}

	private static InstanceDetail toInstanceDetail(Map.Entry<String, ApplicationInstanceInfo> entry, ApplicationStatisticsResponse statisticsResponse) {
		InstanceStatistics instanceStatistics = Optional.ofNullable(statisticsResponse.getInstances().get(entry.getKey())).orElse(emptyInstanceStats());
		Statistics stats = Optional.ofNullable(instanceStatistics.getStatistics()).orElse(emptyApplicationStatistics());
		Usage usage = Optional.ofNullable(stats.getUsage()).orElse(emptyApplicationUsage());

		return InstanceDetail.builder()
			.state(entry.getValue().getState())
			.since(toDate(entry.getValue().getSince()))
			.cpu(usage.getCpu())
			.memoryUsage(usage.getMemory())
			.diskUsage(usage.getDisk())
			.diskQuota(stats.getDiskQuota())
			.memoryQuota(stats.getMemoryQuota())
			.build();
	}

	private static String toUrl(Route route) {
		String hostName = route.getHost();
		String domainName = route.getDomain().getName();

		return hostName.isEmpty() ? domainName : String.format("%s.%s", hostName, domainName);
	}

	private static Statistics emptyApplicationStatistics() {
		return Statistics.builder()
			.usage(emptyApplicationUsage())
			.build();
	}

	private static Usage emptyApplicationUsage() {
		return Usage.builder()
			.build();
	}

	private static InstanceStatistics emptyInstanceStats() {
		return InstanceStatistics.builder()
			.statistics(emptyApplicationStatistics())
			.build();
	}

	private static Date toDate(String date) {
		return date == null ? null : DateUtils.parseFromIso8601(date);
	}

	private static Date toDate(Double date) {
		return date == null ? null : DateUtils.parseSecondsFromEpoch(date);
	}

}
