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

package com.netflix.spinnaker.clouddriver.cf.cache

import com.netflix.spinnaker.cats.cache.Cache
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.cache.RelationshipCacheFilter
import com.netflix.spinnaker.clouddriver.cf.config.CloudFoundryConstants
import com.netflix.spinnaker.clouddriver.cf.model.*
import com.netflix.spinnaker.clouddriver.cf.provider.ProviderUtils
import com.netflix.spinnaker.clouddriver.cf.utils.CloudFoundryJavaClientUtils
import com.netflix.spinnaker.clouddriver.model.LoadBalancerInstance
import com.netflix.spinnaker.clouddriver.model.LoadBalancerServerGroup
import org.cloudfoundry.operations.applications.ApplicationDetail
import org.cloudfoundry.operations.applications.InstanceDetail
import org.cloudfoundry.operations.organizations.OrganizationDetail
import org.cloudfoundry.operations.organizations.OrganizationQuota
import org.cloudfoundry.operations.services.ServiceInstance
import org.cloudfoundry.operations.spaces.SpaceDetail

import static com.netflix.spinnaker.clouddriver.cf.cache.Keys.Namespace.*

class CacheUtils {

  static Collection<CloudFoundryCluster> translateClusters(Cache cacheView, Collection<CacheData> clusterData, boolean includeDetails) {

    Map<String, CloudFoundryLoadBalancer> loadBalancers
    Map<String, CloudFoundryServerGroup> serverGroups

    if (includeDetails) {
      Collection<CacheData> allLoadBalancers = ProviderUtils.resolveRelationshipDataForCollection(cacheView, clusterData, LOAD_BALANCERS.ns)
      Collection<CacheData> allServerGroups = ProviderUtils.resolveRelationshipDataForCollection(cacheView, clusterData, SERVER_GROUPS.ns, RelationshipCacheFilter.include(INSTANCES.ns, LOAD_BALANCERS.ns))

      loadBalancers = translateLoadBalancers(allLoadBalancers)
      serverGroups = translateServerGroups(cacheView, allServerGroups, loadBalancers)
    }

    def clusters = clusterData.collect { CacheData clusterDataEntry ->
      Map<String, String> clusterKey = Keys.parse(clusterDataEntry.id)

      def cluster = new CloudFoundryCluster([
          name: clusterKey.cluster,
          accountName: clusterKey.account
      ])

      if (includeDetails) {
        cluster.loadBalancers = clusterDataEntry.relationships[LOAD_BALANCERS.ns]?.findResults { loadBalancers.get(it) }
        cluster.serverGroups = clusterDataEntry.relationships[SERVER_GROUPS.ns]?.findResults { serverGroups.get(it) }
      } else {
        cluster.loadBalancers = clusterDataEntry.relationships[LOAD_BALANCERS.ns]?.collect { loadBalancerKey ->
          Map parts = Keys.parse(loadBalancerKey)
          new CloudFoundryLoadBalancer(name: parts.loadBalancer, account: parts.account, region: parts.region)
        }
        cluster.serverGroups = clusterDataEntry.relationships[SERVER_GROUPS.ns]?.collect { serverGroupKey ->
          Map parts = Keys.parse(serverGroupKey)
          new CloudFoundryServerGroup(name: parts.serverGroup)
        }
      }

      cluster
    }

    clusters
  }

  static Map<String, CloudFoundryServerGroup> translateServerGroups(Cache cacheView, Collection<CacheData> serverGroupData,
                                                                    Map<String, CloudFoundryLoadBalancer> allLoadBalancers) {
    Collection<CacheData> allInstances = ProviderUtils.resolveRelationshipDataForCollection(cacheView, serverGroupData, INSTANCES.ns)
    Map<String, CloudFoundryApplicationInstance> instances = translateInstances(cacheView, allInstances)

    serverGroupData.collectEntries { serverGroupEntry ->
      def account = Keys.parse(serverGroupEntry.id).account
      def serverGroup = translateServerGroup(serverGroupEntry, instances.values(), allLoadBalancers.values().findAll {it.account == account})
      [(serverGroupEntry.id) : serverGroup]
    }
  }

  static CloudFoundryServerGroup translateServerGroup(CacheData serverGroupEntry,
                                                      Collection<CloudFoundryApplicationInstance> instances,
                                                      Collection<CloudFoundryLoadBalancer> loadBalancers) {

    def serverGroup = new CloudFoundryServerGroup(
        name: serverGroupEntry.attributes.name,
        consoleLink: serverGroupEntry.attributes.consoleLink,
        logsLink: serverGroupEntry.attributes.logsLink,
        nativeApplication: ApplicationDetail.builder()
          .stack(serverGroupEntry.attributes.nativeApplication.stack)
          .urls(serverGroupEntry.attributes.nativeApplication.urls)
          .requestedState(serverGroupEntry.attributes.nativeApplication.requestedState)
          .runningInstances(serverGroupEntry.attributes.nativeApplication.runningInstances)
          .memoryLimit(serverGroupEntry.attributes.nativeApplication.memoryLimit)
          .lastUploaded(new Date(serverGroupEntry.attributes.nativeApplication.lastUploaded))
          .diskQuota(serverGroupEntry.attributes.nativeApplication.diskQuota)
          .buildpack(serverGroupEntry.attributes.nativeApplication.buildpack)
          .instances(serverGroupEntry.attributes.nativeApplication.instances)
          .name(serverGroupEntry.attributes.nativeApplication.name)
          .id(serverGroupEntry.attributes.nativeApplication.id)
          .build()
    )

    serverGroup.memory = serverGroup.nativeApplication.memoryLimit
    serverGroup.disk = serverGroup.nativeApplication.diskQuota

    serverGroup.services = serverGroupEntry.attributes.services.collect {
      new CloudFoundryService([
          type: it.type,
          id: it.id,
          name: it.name,
          application: it.application,
          accountName: it.accountName,
          region: it.region,
          nativeService: ServiceInstance.builder()
              .id(it.nativeService.id)
              .name(it.nativeService.name)
              .type(CloudFoundryJavaClientUtils.serviceInstanceTypeFrom(it.nativeService.type))
              .dashboardUrl(it.nativeService.dashboardUrl)
              .description(it.nativeService.description)
              .documentationUrl(it.nativeService.documentationUrl)
              .plan(it.nativeService.plan)
              .service(it.nativeService.service)
              .status(it.nativeService.status)
              .message(it.nativeService.message)
              .build()
      ])
    }

    serverGroup.buildInfo = [
        commit: serverGroupEntry.attributes.environments[CloudFoundryConstants.COMMIT_HASH] ?: '',
        branch: serverGroupEntry.attributes.environments[CloudFoundryConstants.COMMIT_BRANCH] ?: '',
        package_name: serverGroupEntry.attributes.environments[CloudFoundryConstants.PACKAGE] ?: '',
        jenkins: [
            fullUrl: serverGroupEntry.attributes.environments[CloudFoundryConstants.JENKINS_HOST] ?: '',
            name: serverGroupEntry.attributes.environments[CloudFoundryConstants.JENKINS_NAME] ?: '',
            number: serverGroupEntry.attributes.environments[CloudFoundryConstants.JENKINS_BUILD] ?: ''
        ]
    ]

    serverGroup.instances = (instances) ? instances.findAll { it.name.contains(serverGroup.name) } : [] as Set

    serverGroup.environments = serverGroupEntry.attributes.environments

    def quota = OrganizationQuota.builder()
        .id(serverGroupEntry.attributes.org.quota.id)
        .name(serverGroupEntry.attributes.org.quota.name)
        .organizationId(serverGroupEntry.attributes.org.quota.organizationId)
        .paidServicePlans(serverGroupEntry.attributes.org.quota.paidServicePlans)
        .instanceMemoryLimit(serverGroupEntry.attributes.org.quota.instanceMemoryLimit)
        .totalMemoryLimit(serverGroupEntry.attributes.org.quota.totalMemoryLimit)
        .totalRoutes(serverGroupEntry.attributes.org.quota.totalRoutes)
        .totalServiceInstances(serverGroupEntry.attributes.org.quota.totalServiceInstances)
        .build()

    serverGroup.org = OrganizationDetail.builder()
      .spaces(serverGroupEntry.attributes.org.spaces)
      .name(serverGroupEntry.attributes.org.name)
      .domains(serverGroupEntry.attributes.org.domains)
      .id(serverGroupEntry.attributes.org.id)
      .quota(quota)
      .build()

    serverGroup.space = SpaceDetail.builder()
      .services(serverGroupEntry.attributes.space.services)
      .organization(serverGroupEntry.attributes.space.organization)
      .domains(serverGroupEntry.attributes.space.domains)
      .applications(serverGroupEntry.attributes.space.applications)
      .name(serverGroupEntry.attributes.space.name)
      .id(serverGroupEntry.attributes.space.id)
      .build()

    def namedLoadBalancers = serverGroup.environments[CloudFoundryConstants.LOAD_BALANCERS]?.split(',') as List
    serverGroup.nativeLoadBalancers = namedLoadBalancers.findResults { route ->
      loadBalancers.find { it.name == route }
    }

    serverGroup.disabled = !serverGroup.nativeApplication.urls?.findResults { url ->
      def lbs = serverGroup.nativeLoadBalancers.collect { loadBalancer ->
        (loadBalancer?.name) ? loadBalancer.name : ''
      }
      lbs.contains(url.split('\\.')[0]) // Just compare the hostname
    }

    serverGroup.nativeLoadBalancers?.each {
      it.serverGroups.add(new LoadBalancerServerGroup(
          name      :        serverGroup.name,
          isDisabled:        serverGroup.isDisabled(),
          detachedInstances: [] as Set,
          instances :        serverGroup.instances.collect {
            new LoadBalancerInstance(
                id: it.name,
                zone: it.zone,
                health: it.health?.get(0)
            )
          },
      ))
    }

    serverGroup
  }

  static Map<String, CloudFoundryApplicationInstance> translateInstances(Cache cacheView, Collection<CacheData> instanceData) {
    instanceData?.collectEntries { instanceEntry ->
      CacheData serverGroup = ProviderUtils.resolveRelationshipData(cacheView, instanceEntry, SERVER_GROUPS.ns)[0]
      Collection<CacheData> allLoadBalancers = ProviderUtils.resolveRelationshipDataForCollection(cacheView, [instanceEntry], LOAD_BALANCERS.ns)
      def loadBalancers = CacheUtils.translateLoadBalancers(allLoadBalancers).values()

      [(instanceEntry.id): translateInstance(instanceEntry, serverGroup, loadBalancers)]
    } ?: [:]
  }

  static CloudFoundryApplicationInstance translateInstance(CacheData instanceEntry, CacheData serverGroup,
                                                           Collection<CloudFoundryLoadBalancer> lbs) {
    def instance = new CloudFoundryApplicationInstance(
        name: instanceEntry.attributes.name,
        nativeInstance: (instanceEntry.attributes.nativeInstance instanceof InstanceDetail)
            ? instanceEntry.attributes.nativeInstance
            : InstanceDetail.builder()
                .cpu(instanceEntry.attributes.nativeInstance.cpu)
                .diskQuota(instanceEntry.attributes.nativeInstance.diskQuota)
                .diskUsage(instanceEntry.attributes.nativeInstance.diskUsage)
                .memoryQuota(instanceEntry.attributes.nativeInstance.memoryQuota)
                .memoryUsage(instanceEntry.attributes.nativeInstance.memoryUsage)
                .since(new Date(instanceEntry.attributes.nativeInstance.since))
                .state(instanceEntry.attributes.nativeInstance.state)
              .build(),
        space: serverGroup.attributes.space.name
    )
    instance.consoleLink = serverGroup.attributes.consoleLink
    instance.logsLink = serverGroup.attributes.logsLink
    instance.healthState = CloudFoundryApplicationInstance.instanceStateToHealthState(instance.nativeInstance.state)
    instance.health = CloudFoundryApplicationInstance.createInstanceHealth(instance)
    instance.loadBalancers = lbs
    instance
  }



  static Map<String, CloudFoundryLoadBalancer> translateLoadBalancers(Collection<CacheData> loadBalancerData) {
    loadBalancerData.collectEntries { loadBalancerEntry ->
      [(loadBalancerEntry.id): translateLoadBalancer(loadBalancerEntry)]
    }
  }

  static CloudFoundryLoadBalancer translateLoadBalancer(CacheData loadBalancerEntry) {
    Map<String, String> lbKey = Keys.parse(loadBalancerEntry.id)
    new CloudFoundryLoadBalancer(name: lbKey.loadBalancer, domain: loadBalancerEntry.attributes.domain, account: lbKey.account, region: lbKey.region)
  }


}
