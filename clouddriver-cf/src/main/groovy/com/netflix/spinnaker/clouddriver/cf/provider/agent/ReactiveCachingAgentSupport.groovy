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
import com.netflix.frigga.Names
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.clouddriver.cf.cache.Keys
import com.netflix.spinnaker.clouddriver.cf.model.CloudFoundryService
import com.netflix.spinnaker.clouddriver.cf.security.CloudFoundryAccountCredentials
import org.cloudfoundry.operations.applications.ApplicationDetail
import org.cloudfoundry.operations.applications.InstanceDetail
import org.cloudfoundry.operations.organizations.OrganizationDetail
import org.cloudfoundry.operations.services.ServiceInstance
import org.cloudfoundry.operations.spaces.SpaceDetail
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static com.netflix.spinnaker.clouddriver.cf.cache.Keys.Namespace.*
/**
 * Groovy support code to assist {@link ReactiveCachingAgent}.
 */
class ReactiveCachingAgentSupport {

  final static Logger log = LoggerFactory.getLogger(ReactiveCachingAgentSupport)

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

  public static Map<String, CacheData> cache() {
    [:].withDefault { String id -> new MutableCacheData(id) }
  }

  public static void cache(Map<String, List<CacheData>> cacheResults, String namespace, Map<String, CacheData> cacheDataById) {
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

  public static void cacheApplications(CloudFoundryData data, Map<String, CacheData> applications) {
    applications[data.appNameKey].with {
      attributes.name = data.name.app

      relationships[CLUSTERS.ns].add(data.clusterKey)
      relationships[SERVER_GROUPS.ns].add(data.serverGroupKey)
      relationships[LOAD_BALANCERS.ns].addAll(data.loadBalancerKeys)
    }
  }

  public static void cacheCluster(CloudFoundryData data, Map<String, CacheData> clusters) {
    clusters[data.clusterKey].with {
      attributes.name = data.name.cluster

      relationships[APPLICATIONS.ns].add(data.appNameKey)
      relationships[SERVER_GROUPS.ns].add(data.serverGroupKey)
      relationships[LOAD_BALANCERS.ns].addAll(data.loadBalancerKeys)
    }
  }

  public static void cacheServerGroup(CloudFoundryData data,
                                      Map<String, CacheData> serverGroups,
                                      List<ServiceInstance> services,
                                      Map<String, Object> environments,
                                      CloudFoundryAccountCredentials account) {
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

  public static void cacheInstances(CloudFoundryData data, Map<String, CacheData> instances, CloudFoundryAccountCredentials account) {
    data.instances.eachWithIndex { instance, index ->
      def id = "${data.application.name}(${index})".toString()
      def instanceIdKey = Keys.getInstanceKey(id, account.name, account.org)
      instances[instanceIdKey].with {
        attributes.name = id
        attributes.nativeInstance = instance

        relationships[SERVER_GROUPS.ns].add(data.serverGroupKey)
        relationships[LOAD_BALANCERS.ns].addAll(data.loadBalancerKeys)
      }
    }
  }

  public static void cacheLoadBalancers(CloudFoundryData data, Map<String, CacheData> loadBalancers) {
    data.loadBalancers.each { loadBalancer ->
      loadBalancers[Keys.getLoadBalancerKey(loadBalancer.name, loadBalancer.account.name, loadBalancer.region)].with {
        attributes.name = loadBalancer.name

        // Avoid overwriting an existing domain (active server group) with a null from an inactive server group
        if (loadBalancer?.domain) {
          attributes.domain = loadBalancer.domain
        }

        attributes.region = loadBalancer.region

        relationships[APPLICATIONS.ns].add(data.appNameKey)
        relationships[SERVER_GROUPS.ns].add(data.serverGroupKey)
        relationships[INSTANCES.ns].addAll(data.instanceIdKeys)
      }
    }
  }

  public static class CloudFoundryData {

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
