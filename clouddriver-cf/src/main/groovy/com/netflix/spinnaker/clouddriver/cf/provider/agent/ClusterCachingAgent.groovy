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

package com.netflix.spinnaker.clouddriver.cf.provider.agent

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.frigga.Names
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.cats.agent.*
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.cache.DefaultCacheData
import com.netflix.spinnaker.cats.provider.ProviderCache
import com.netflix.spinnaker.clouddriver.cache.OnDemandAgent
import com.netflix.spinnaker.clouddriver.cache.OnDemandMetricsSupport
import com.netflix.spinnaker.clouddriver.cf.CloudFoundryCloudProvider
import com.netflix.spinnaker.clouddriver.cf.cache.Keys
import com.netflix.spinnaker.clouddriver.cf.config.CloudFoundryConstants
import com.netflix.spinnaker.clouddriver.cf.model.CloudFoundryService
import com.netflix.spinnaker.clouddriver.cf.provider.CloudFoundryProvider
import com.netflix.spinnaker.clouddriver.cf.security.CloudFoundryAccountCredentials
import com.netflix.spinnaker.clouddriver.cf.utils.CloudFoundryClientFactory
import org.cloudfoundry.client.CloudFoundryClient
import org.cloudfoundry.client.v2.servicebindings.ListServiceBindingsRequest
import org.cloudfoundry.client.v2.serviceinstances.GetServiceInstanceRequest
import org.cloudfoundry.client.v2.serviceinstances.ServiceInstanceEntity
import org.cloudfoundry.operations.CloudFoundryOperations
import org.cloudfoundry.operations.applications.*
import org.cloudfoundry.operations.organizations.OrganizationDetail
import org.cloudfoundry.operations.organizations.OrganizationInfoRequest
import org.cloudfoundry.operations.routes.Level
import org.cloudfoundry.operations.routes.ListRoutesRequest
import org.cloudfoundry.operations.routes.Route
import org.cloudfoundry.operations.services.ServiceInstance
import org.cloudfoundry.operations.spaces.GetSpaceRequest
import org.cloudfoundry.operations.spaces.SpaceDetail
import org.cloudfoundry.util.PaginationUtils
import org.cloudfoundry.util.ResourceUtils
import org.cloudfoundry.util.tuple.Function2
import org.cloudfoundry.util.tuple.Predicate2
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

import java.time.Duration

import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.AUTHORITATIVE
import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.INFORMATIVE
import static com.netflix.spinnaker.clouddriver.cf.cache.Keys.Namespace.*
import static org.cloudfoundry.util.tuple.TupleUtils.function
import static org.cloudfoundry.util.tuple.TupleUtils.predicate

class ClusterCachingAgent implements CachingAgent, OnDemandAgent, AccountAware {

  final static Logger log = LoggerFactory.getLogger(ClusterCachingAgent)

  private static final TypeReference<Map<String, Object>> ATTRIBUTES = new TypeReference<Map<String, Object>>() {}

  static final Set<AgentDataType> types = Collections.unmodifiableSet([
      AUTHORITATIVE.forType(SERVER_GROUPS.ns),
      INFORMATIVE.forType(CLUSTERS.ns),
      INFORMATIVE.forType(APPLICATIONS.ns),
      AUTHORITATIVE.forType(INSTANCES.ns),
      AUTHORITATIVE.forType(LOAD_BALANCERS.ns)
  ] as Set)

  final CloudFoundryClientFactory cloudFoundryClientFactory
  final CloudFoundryAccountCredentials account
  final ObjectMapper objectMapper
  final Registry registry

  final OnDemandMetricsSupport metricsSupport

  ClusterCachingAgent(CloudFoundryClientFactory cloudFoundryClientFactory,
                      CloudFoundryAccountCredentials account,
                      ObjectMapper objectMapper,
                      Registry registry) {
    this.objectMapper = objectMapper
    this.account = account
    this.cloudFoundryClientFactory = cloudFoundryClientFactory
    this.registry = registry
    this.metricsSupport = new OnDemandMetricsSupport(registry, this, "${CloudFoundryCloudProvider.ID}:${OnDemandAgent.OnDemandType.ServerGroup}")
  }

  @Override
  String getProviderName() {
    CloudFoundryProvider.PROVIDER_NAME
  }

  @Override
  String getAgentType() {
    "${account.name}/${ClusterCachingAgent.simpleName}"
  }

  @Override
  String getAccountName() {
    account.name
  }

  @Override
  String getOnDemandAgentType() {
    "${agentType}-OnDemand"
  }


  @Override
  Collection<AgentDataType> getProvidedDataTypes() {
    types
  }

  static class MutableCacheData implements CacheData {
    final String id
    int ttlSeconds = -1
    final Map<String, Object> attributes = [:]
    final Map<String, Collection<String>> relationships = [:].withDefault { [] as Set }

    public MutableCacheData(String id) {
      this.id = id
    }

    @JsonCreator
    public MutableCacheData(@JsonProperty("id") String id,
                            @JsonProperty("attributes") Map<String, Object> attributes,
                            @JsonProperty("relationships") Map<String, Collection<String>> relationships) {
      this(id);
      this.attributes.putAll(attributes);
      this.relationships.putAll(relationships);
    }
  }

  @Override
  boolean handles(OnDemandAgent.OnDemandType type, String cloudProvider) {
    type == OnDemandAgent.OnDemandType.ServerGroup && cloudProvider == CloudFoundryCloudProvider.ID
  }

  @Override
  OnDemandAgent.OnDemandResult handle(ProviderCache providerCache, Map<String, ? extends Object> data) {
    if (!data.containsKey("serverGroupName")) {
      return null
    }
    if (!data.containsKey("account")) {
      return null
    }
    if (!data.containsKey("region")) {
      return null
    }

    if (account.name != data.account) {
      return null
    }

    if (account.org != data.region) {
      return null
    }

    String serverGroupName = data.serverGroupName.toString()

    def operations = cloudFoundryClientFactory.createCloudFoundryOperations(account, true)
    def client = cloudFoundryClientFactory.createCloudFoundryClient(account, true)

    SpaceDetail currentSpace = lookupCurrentSpace(operations).block(Duration.ofMinutes(5))
    OrganizationDetail currentOrg = lookupCurrentOrg(operations).block(Duration.ofMinutes(5))
    List<Route> routes = lookupRoutes(operations).collectList().block(Duration.ofSeconds(30))

    def cacheResult = metricsSupport.transformData {
      buildCacheResult(operations, client, currentOrg, currentSpace, routes, [:], [], Long.MAX_VALUE)
    }

    if (cacheResult.cacheResults.values().flatten().isEmpty()) {
      // Avoid writing an empty onDemand cache record (instead delete any that may have previously existed).
      providerCache.evictDeletedItems(ON_DEMAND.ns, [Keys.getServerGroupKey(serverGroupName, account.name, account.org)])
    } else {
      metricsSupport.onDemandStore {
        def resultsAsJson = objectMapper.writeValueAsString(cacheResult.cacheResults)
        def cacheData = new DefaultCacheData(
            Keys.getServerGroupKey(serverGroupName, account.name, account.org),
            10 * 60,
            [
                cacheTime     : System.currentTimeMillis(),
                cacheResults  : resultsAsJson,
                processedCount: 0,
                processedTime : null
            ],
            [:]
        )

        providerCache.putCacheData(ON_DEMAND.ns, cacheData)
      }
    }

    Collection<ApplicationDetail> onDemandData = [metricsSupport.readData {

      operations.applications()
          .get(GetApplicationRequest.builder()
          .name(serverGroupName)
          .build())
    }
    .block(Duration.ofSeconds(30))] ?: []

    Map<String, Collection<String>> evictions = !onDemandData.isEmpty() ? [:] : [
        (SERVER_GROUPS.ns): [
            Keys.getServerGroupKey(serverGroupName, account.name, account.org)
        ]
    ]

    log.info("onDemand cache refresh (data: ${data}, evictions: ${evictions})")

    return new OnDemandAgent.OnDemandResult(
        sourceAgentType: getOnDemandAgentType(), cacheResult: cacheResult, evictions: evictions
    )
  }

  private Flux<Route> lookupRoutes(CloudFoundryOperations operations) {
    operations.routes()
      .list(ListRoutesRequest.builder()
        .level(Level.SPACE)
        .build())
  }

  private Mono<SpaceDetail> lookupCurrentSpace(CloudFoundryOperations operations) {
    operations.spaces()
      .get(GetSpaceRequest.builder()
        .name(account.space)
        .build())
  }

  private Mono<OrganizationDetail> lookupCurrentOrg(CloudFoundryOperations operations) {
    operations.organizations()
      .get(OrganizationInfoRequest.builder()
        .name(account.org)
        .build())
  }

  @Override
  Collection<Map> pendingOnDemandRequests(ProviderCache providerCache) {
    def keys = providerCache.getIdentifiers(ON_DEMAND.ns)
    providerCache.getAll(ON_DEMAND.ns, keys).collect {
      [
        id: it.id,
        details: Keys.parse(it.id),
        cacheTime: it.attributes.cacheTime,
        processedCount: it.attributes.processedCount ?: 0,
        processedTime: it.attributes.processedTime
      ]
    }
  }

  @Override
  CacheResult loadData(ProviderCache providerCache) {
    Long start = System.currentTimeMillis()

    log.info "Describing items in ${agentType}"

    def operations = cloudFoundryClientFactory.createCloudFoundryOperations(account, true)
    def client = cloudFoundryClientFactory.createCloudFoundryClient(account, true)

    def currentOrg = lookupCurrentOrg(operations).block(Duration.ofMinutes(5))
    def currentSpace = lookupCurrentSpace(operations).block(Duration.ofMinutes(5))
    def routes = lookupRoutes(operations).collectList().block(Duration.ofSeconds(30))

    List<ApplicationSummary> apps = operations.applications().list().collectList().block(Duration.ofSeconds(30))

    def evictableOnDemandCacheDatas = []
    def keepInOnDemand = []

    providerCache.getAll(ON_DEMAND.ns, apps
        .collect({app -> Keys.getServerGroupKey(app.name, account.name, account.org)})).each {
      if (new Long(it.attributes.cacheTime) < start && it.attributes.processedCount > 0) {
        evictableOnDemandCacheDatas << it
      } else {
        keepInOnDemand << it
      }
    }

    def stuffToConsider = keepInOnDemand.collectEntries { [(it.id), it] }

    def result = buildCacheResult(operations, client, currentOrg, currentSpace, routes,
        stuffToConsider, evictableOnDemandCacheDatas*.id, start)

    result.cacheResults[ON_DEMAND.ns].each {
      it.attributes.processedTime = System.currentTimeMillis()
      it.attributes.processedCount = (it.attributes.processedCount ?: 0) + 1
    }

    result
  }

  private CacheResult buildCacheResult(CloudFoundryOperations operations,
                                        CloudFoundryClient client,
                                       OrganizationDetail currentOrg,
                                       SpaceDetail currentSpace,
                                       Collection<Route> routes,
                                       Map<String, CacheData> onDemandKeep,
                                       Collection<String> evictableOnDemandCacheDataIdentifiers,
                                       Long start) {

    Map<String, CacheData> applications = cache()
    Map<String, CacheData> clusters = cache()
    Map<String, CacheData> serverGroups = cache()
    Map<String, CacheData> instances = cache()
    Map<String, CacheData> loadBalancers = cache()

    operations.applications()
      .list()
      .flatMap({ ApplicationSummary appSummary ->
        operations.applications()
          .getEnvironments(GetApplicationEnvironmentsRequest.builder()
            .name(appSummary.name)
            .build())
          .and(Mono.just(appSummary))
      })
      .log('mapAppToEnv')
      .filter(predicate({ ApplicationEnvironments environments, ApplicationSummary application ->
        environments?.userProvided?.containsKey(CloudFoundryConstants.LOAD_BALANCERS) ?: false
      } as Predicate2))
      .log('filterForLoadBalancers')
      .flatMap(function({ ApplicationEnvironments environments, ApplicationSummary application ->
        operations.applications()
          .get(GetApplicationRequest.builder()
            .name(application.name)
            .build())
          .and(Mono.just(environments))
      } as Function2))
      .log('getApplicationDetails')
      .flatMap(function({ ApplicationDetail app, ApplicationEnvironments environments ->
        cacheUpResults(app, applications, clusters, instances, loadBalancers, serverGroups, currentOrg, currentSpace, account, objectMapper, start, operations, client, onDemandKeep, environments)
      } as Function2))
      .log('cacheUpResults')
      .then()
      .block(Duration.ofMinutes(5))

    new DefaultCacheResult([
      (APPLICATIONS.ns): applications.values(),
      (CLUSTERS.ns): clusters.values(),
      (SERVER_GROUPS.ns): serverGroups.values(),
      (INSTANCES.ns): instances.values(),
      (LOAD_BALANCERS.ns): loadBalancers.values(),
      (ON_DEMAND.ns): onDemandKeep.values()
    ],[
      (ON_DEMAND.ns): evictableOnDemandCacheDataIdentifiers
    ])
  }

  private static Flux<Map> lookupLoadBalancers(CloudFoundryOperations operations, CloudFoundryAccountCredentials account, ApplicationDetail app) {

    operations.applications()
      .getEnvironments(GetApplicationEnvironmentsRequest.builder()
        .name(app.name)
        .build())
      .log('gettingEnvironmentsFor ' + app.name)
      .flatMap({ envs -> Flux.fromArray((envs?.userProvided[CloudFoundryConstants.LOAD_BALANCERS] ?: '').split(',')) })
      .log('flatMapping for ' + app.name)
      .map({ route -> [
          name   : route,
          region : account.org,
          account: account]
      })
  }

  private static Flux<ServiceInstanceEntity> lookupRelatedServices(CloudFoundryClient client, app) {

    PaginationUtils.requestClientV2Resources({ page ->
      client.serviceBindingsV2()
        .list(ListServiceBindingsRequest.builder()
          .applicationId(app.id)
          .page(page)
          .build())
    })
    .map({ it.entity.serviceInstanceId })
    .flatMap({ serviceInstanceId ->
      client.serviceInstances()
        .get(GetServiceInstanceRequest.builder()
          .serviceInstanceId(serviceInstanceId)
          .build())
    })
    .map({ ResourceUtils.&getEntity })
  }

  private Mono cacheUpResults(ApplicationDetail app,
                                     Map<String, CacheData> applications,
                                     Map<String, CacheData> clusters,
                                     Map<String, CacheData> instances,
                                     Map<String, CacheData> loadBalancers,
                                     Map<String, CacheData> serverGroups,
                                     OrganizationDetail currentOrg,
                                     SpaceDetail currentSpace,
                                     CloudFoundryAccountCredentials account,
                                     ObjectMapper objectMapper,
                                     long start,
                                     CloudFoundryOperations operations,
                                      CloudFoundryClient client,
                                     Map<String, CacheData> onDemandKeep,
                                     ApplicationEnvironments environments) {
    def onDemandData = onDemandKeep ?
        onDemandKeep[Keys.getServerGroupKey(app.name, account.name, account.org)] : null

    if (onDemandData && new Long(onDemandData.attributes.cacheTime) >= start) {
      log.info("Using onDemand cache value (${onDemandData.id})")
      Map<String, List<CacheData>> cacheResults = objectMapper.readValue(onDemandData.attributes.cacheResults as String, new TypeReference<Map<String, List<MutableCacheData>>>() {
      })
      cache(cacheResults, APPLICATIONS.ns, applications)
      cache(cacheResults, CLUSTERS.ns, clusters)
      cache(cacheResults, SERVER_GROUPS.ns, serverGroups)
      cache(cacheResults, INSTANCES.ns, instances)
      cache(cacheResults, LOAD_BALANCERS.ns, loadBalancers)
      return Mono.just(cacheResults)
    } else {
      Flux<Map> declaredLoadBalancers = lookupLoadBalancers(operations, account, app)
      Flux<ServiceInstanceEntity> relatedServices = lookupRelatedServices(client, app)

      return declaredLoadBalancers.collectList()
        .and(relatedServices.collectList())
        .log('lbsAndServices')
        .then(function({ List<Map> lbs, List<ServiceInstance> svcs ->
          CloudFoundryData data = new CloudFoundryData(app, account, lbs as Set, currentOrg, currentSpace)

          cacheApplications(data, applications)
          cacheCluster(data, clusters)
          cacheInstances(data, instances)
          cacheLoadBalancers(data, loadBalancers)
          cacheServerGroup(data, serverGroups, svcs, environments)
          Mono.just(data)
      } as Function2))
    }
  }


  private Map<String, CacheData> cache() {
    [:].withDefault { String id -> new MutableCacheData(id) }
  }

  private static void cache(Map<String, List<CacheData>> cacheResults, String namespace, Map<String, CacheData> cacheDataById) {
    cacheResults[namespace].each {
      def existingCacheData = cacheDataById[it.id]
      if (!existingCacheData) {
        cacheDataById[it.id] = it
      } else {
        existingCacheData.attributes.putAll(it.attributes)
        it.relationships.each { String relationshipName, Collection<String> relationships ->
          existingCacheData.relationships[relationshipName].addAll(relationships)
        }
      }
    }
  }


  private void cacheApplications(CloudFoundryData data, Map<String, CacheData> applications) {
    applications[data.appNameKey].with {
      attributes.name = data.name.app

      relationships[CLUSTERS.ns].add(data.clusterKey)
      relationships[SERVER_GROUPS.ns].add(data.serverGroupKey)
      relationships[LOAD_BALANCERS.ns].addAll(data.loadBalancerKeys)
    }
  }

  private void cacheCluster(CloudFoundryData data, Map<String, CacheData> clusters) {
    clusters[data.clusterKey].with {
      attributes.name = data.name.cluster

      relationships[APPLICATIONS.ns].add(data.appNameKey)
      relationships[SERVER_GROUPS.ns].add(data.serverGroupKey)
      relationships[LOAD_BALANCERS.ns].addAll(data.loadBalancerKeys)
    }
  }

  private void cacheServerGroup(CloudFoundryData data, Map<String, CacheData> serverGroups,
                                List<ServiceInstance> services, ApplicationEnvironments environments) {
    serverGroups[data.serverGroupKey].with {
      attributes.name = data.application.name
      attributes.logsLink = "${account.console}/organizations/${data.org.id}/spaces/${data.space.id}/applications/${data.application.id}/logs".toString()
      attributes.consoleLink = "${account.console}/organizations/${data.org.id}/spaces/${data.space.id}/applications/${data.application.id}".toString()
      attributes.nativeApplication = data.application
      attributes.services = services.collect {
        new CloudFoundryService([
            type: 'cf',
            id: it.id,
            name: it.name,
            application: data.name.app,
            accountName: account.name,
            region: data.org.name,
            nativeService: it
        ])
      }
      attributes.environments = environments
      attributes.org = data.org
      attributes.space = data.space

      relationships[APPLICATIONS.ns].add(data.appNameKey)
      relationships[CLUSTERS.ns].add(data.clusterKey)
      relationships[INSTANCES.ns].addAll(data.instanceIdKeys)
      relationships[LOAD_BALANCERS.ns].addAll(data.loadBalancerKeys)
    }
  }

  private void cacheInstances(CloudFoundryData data, Map<String, CacheData> instances) {
    data.instances.eachWithIndex { instance, index ->
      def id = "${data.application.name}(${index})".toString()
      def instanceIdKey = Keys.getInstanceKey(id, account.name, account.org)
      instances[instanceIdKey].with {
        attributes.name = id
        attributes.nativeInstance = instance

        relationships[SERVER_GROUPS.ns].add(data.serverGroupKey)
      }
    }
  }

  private void cacheLoadBalancers(CloudFoundryData data, Map<String, CacheData> loadBalancers) {
    data.loadBalancers.each { loadBalancer ->
      loadBalancers[Keys.getLoadBalancerKey(loadBalancer.name, loadBalancer.account.name, loadBalancer.region)].with {
        attributes.name = loadBalancer.name
        attributes.region = loadBalancer.region
//        attributes.nativeRoute = loadBalancer.nativeRoute

        relationships[APPLICATIONS.ns].add(data.appNameKey)
        relationships[SERVER_GROUPS.ns].add(data.serverGroupKey)
        relationships[INSTANCES.ns].addAll(data.instanceIdKeys)
      }
    }
  }

  private static class CloudFoundryData {
    final ApplicationDetail application
    final Names name
    final List<InstanceDetail> instances
    final Set<Map> loadBalancers

    final String appNameKey
    final String clusterKey
    final String serverGroupKey
    final Set<String> instanceIdKeys
    final Set<String> loadBalancerKeys
    final OrganizationDetail org
    final SpaceDetail space

    public CloudFoundryData(ApplicationDetail application,
                            CloudFoundryAccountCredentials account,
                            Set<Map> loadBalancers,
                            OrganizationDetail currentOrg,
                            SpaceDetail currentSpace) {
      this.application = application
      this.name = Names.parseName(application.name)
      this.instances = application.instanceDetails
      this.loadBalancers = loadBalancers
      this.appNameKey = Keys.getApplicationKey(name.app)
      this.clusterKey = Keys.getClusterKey(name.cluster, name.app, account.name)
      this.serverGroupKey = Keys.getServerGroupKey(application.name, account.name, account.org)
      this.org = currentOrg
      this.space = currentSpace

      def index = -1
      this.instanceIdKeys = this.instances?.collect { InstanceDetail it ->
        index++
        Keys.getInstanceKey("${application.name}(${index})".toString(), account.name, account.org)
      } ?: []

      this.loadBalancerKeys = this.loadBalancers?.collect {
        Keys.getLoadBalancerKey(it.name, it.account.name, it.region)
      } ?: []
    }
  }

}
