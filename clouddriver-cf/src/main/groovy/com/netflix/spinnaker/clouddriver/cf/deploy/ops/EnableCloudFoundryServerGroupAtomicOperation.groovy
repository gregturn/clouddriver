/*
 * Copyright 2015 Pivotal Inc.
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

package com.netflix.spinnaker.clouddriver.cf.deploy.ops
import com.netflix.frigga.Names
import com.netflix.spinnaker.clouddriver.cf.config.CloudFoundryConstants
import com.netflix.spinnaker.clouddriver.cf.deploy.description.EnableDisableCloudFoundryServerGroupDescription
import com.netflix.spinnaker.clouddriver.cf.provider.view.CloudFoundryClusterProvider
import com.netflix.spinnaker.clouddriver.cf.utils.CloudFoundryClientFactory
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.helpers.OperationPoller
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation
import org.cloudfoundry.operations.applications.*
import org.cloudfoundry.operations.organizations.OrganizationDetail
import org.cloudfoundry.operations.organizations.OrganizationInfoRequest
import org.cloudfoundry.operations.routes.MapRouteRequest
import org.cloudfoundry.util.tuple.Function2
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.function.Tuples

import java.time.Duration
import java.util.function.Function

import static org.cloudfoundry.util.tuple.TupleUtils.function

class EnableCloudFoundryServerGroupAtomicOperation implements AtomicOperation<Void> {

  final String phaseName = "ENABLE_SERVER_GROUP"

  EnableDisableCloudFoundryServerGroupDescription description

  @Autowired
  CloudFoundryClientFactory cloudFoundryClientFactory

  @Autowired
  CloudFoundryClusterProvider clusterProvider

  @Autowired
  TaskRepository taskRepository

  @Autowired
  @Qualifier('cloudFoundryOperationPoller')
  OperationPoller operationPoller

  EnableCloudFoundryServerGroupAtomicOperation(EnableDisableCloudFoundryServerGroupDescription description) {
    this.description = description
  }

  @Override
  Void operate(List priorOutputs) {

    def task = taskRepository.create(phaseName, "Initializing enable server group operation for $description.serverGroupName in ${description.region}...")
    TaskRepository.threadLocalTask.set(task)

    Names names = Names.parseName(description.serverGroupName)
    if (description.nativeLoadBalancers == null) {
      description.nativeLoadBalancers = clusterProvider.getCluster(names.app, description.accountName, names.cluster)?.
          serverGroups.find {it.name == description.serverGroupName}?.nativeLoadBalancers
    }

    def operations = cloudFoundryClientFactory.createCloudFoundryOperations(description.credentials, true)

    task.updateStatus phaseName, "Looking up organization for ${description.serverGroupName}"

    operations.organizations()
      .get(OrganizationInfoRequest.builder()
        .name(description.region)
        .build())
      .then({OrganizationDetail org ->
        task.updateStatus phaseName, "Looking up env settings for ${description.serverGroupName}"

        operations.applications()
          .getEnvironments(GetApplicationEnvironmentsRequest.builder()
            .name(description.serverGroupName)
            .build())
        .and(Mono.just(org))
      } as Function)
      .flatMap(function({ ApplicationEnvironments env, OrganizationDetail org ->
        task.updateStatus phaseName, "Looking up load balancers associated with ${description.serverGroupName}"

        def userProvided = env.userProvided ?: [:]
        def loadBalancerHosts = userProvided[CloudFoundryConstants.LOAD_BALANCERS]?.split(',') as List ?: []
        if (loadBalancerHosts.empty) {
          task.updateStatus phaseName, "${description.serverGroupName} is not linked to any load balancers and can NOT be enabled"
          throw new RuntimeException("${description.serverGroupName} is not linked to any load balancers and can NOT be enabled")
        }
        loadBalancerHosts += description.serverGroupName

        Flux.fromIterable(loadBalancerHosts.collect { loadBalancerHost ->
          Tuples.of(loadBalancerHost, org)
        })
      } as Function2))
      .concatMap(function({ String loadBalancerHost, OrganizationDetail org ->
        task.updateStatus phaseName, "Registering ${description.serverGroupName} instances with load balancer ${loadBalancerHost}..."

        operations.routes()
          .map(MapRouteRequest.builder()
            .applicationName(description.serverGroupName)
            .host(loadBalancerHost)
            .domain(org.domains[0])
            .build())
      } as Function2))
      .collectList()
      .then({
        operations.applications()
          .start(StartApplicationRequest.builder()
          .name(description.serverGroupName)
          .build())
      } as Function)
      .block(Duration.ofMinutes(10))

    task.updateStatus phaseName, "Done enabling server group $description.serverGroupName in $description.region."

    null
  }

}
