/*
 * Copyright 2016 Pivotal Inc.
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
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spectator.api.DefaultRegistry
import com.netflix.spinnaker.cats.mem.InMemoryNamedCacheFactory
import com.netflix.spinnaker.cats.provider.DefaultProviderRegistry
import com.netflix.spinnaker.cats.provider.ProviderRegistry
import com.netflix.spinnaker.clouddriver.cf.TestCredential
import com.netflix.spinnaker.clouddriver.cf.config.CloudFoundryConstants
import com.netflix.spinnaker.clouddriver.cf.deploy.description.EnableDisableCloudFoundryServerGroupDescription
import com.netflix.spinnaker.clouddriver.cf.provider.CloudFoundryProvider
import com.netflix.spinnaker.clouddriver.cf.provider.agent.ReactiveCachingAgentSupport
import com.netflix.spinnaker.clouddriver.cf.provider.view.CloudFoundryClusterProvider
import com.netflix.spinnaker.clouddriver.cf.security.TestCloudFoundryClientFactory
import com.netflix.spinnaker.clouddriver.cf.utils.CloudFoundryClientFactory
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.helpers.OperationPoller
import org.cloudfoundry.operations.CloudFoundryOperations
import org.cloudfoundry.operations.applications.ApplicationDetail
import org.cloudfoundry.operations.applications.ApplicationEnvironments
import org.cloudfoundry.operations.applications.ApplicationSummary
import org.cloudfoundry.operations.applications.Applications
import org.cloudfoundry.operations.applications.GetApplicationEnvironmentsRequest
import org.cloudfoundry.operations.applications.GetApplicationRequest
import org.cloudfoundry.operations.applications.InstanceDetail
import org.cloudfoundry.operations.routes.Routes
import org.cloudfoundry.operations.services.ServiceInstance
import org.cloudfoundry.operations.services.Services
import org.cloudfoundry.operations.spaces.GetSpaceRequest
import org.cloudfoundry.operations.spaces.SpaceDetail
import org.cloudfoundry.operations.spaces.Spaces
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import spock.lang.Ignore
import spock.lang.Specification

@Ignore
class EnableCloudFoundryServerGroupAtomicOperationSpec extends Specification {

  Task task

  CloudFoundryOperations operations

  Applications applications

  Spaces spaces

  Routes routes

  Services services

  CloudFoundryClientFactory cloudFoundryClientFactory

  ReactiveCachingAgentSupport cachingAgent

  ProviderRegistry registry

  CloudFoundryClusterProvider clusterProvider

//  // Generated via https://www.uuidgenerator.net/version4
//  final String uuid1 = '35807c3d-d71b-486a-a7c7-0d351b62dace'
//  final String uuid2 = 'e6d70139-5415-48b3-adf3-a35471f70ab5'
//  final String uuid3 = '78d845c9-900e-4144-be09-63d4f433a2fd'
//
  def setup() {
    task = Mock(Task)
    TaskRepository.threadLocalTask.set(task)

    operations = Mock(CloudFoundryOperations)
    applications = Mock(Applications)
    spaces = Mock(Spaces)
    routes = Mock(Routes)
    services = Mock(Services)

    cloudFoundryClientFactory = new TestCloudFoundryClientFactory(stubOperations: operations)
    cachingAgent = new ReactiveCachingAgentSupport(
        new TestCloudFoundryClientFactory(stubOperations: operations),
        TestCredential.named('baz'),
        new ObjectMapper(),
        new DefaultRegistry()
    )

    def cloudFoundryProvider = new CloudFoundryProvider([cachingAgent])
    registry = new DefaultProviderRegistry([cloudFoundryProvider], new InMemoryNamedCacheFactory())
    clusterProvider = new CloudFoundryClusterProvider(registry.getProviderCache(CloudFoundryProvider.PROVIDER_NAME), cloudFoundryProvider, new ObjectMapper())

  }

//  void "should bubble up exception if server group doesn't exist"() {
//    given:
//    def serverGroupName = "my-stack-v000"
//    def op = new EnableCloudFoundryServerGroupAtomicOperation(
//        new EnableDisableCloudFoundryServerGroupDescription(
//            serverGroupName: serverGroupName,
//            region: "test",
//            credentials: TestCredential.named('baz')))
//    op.cloudFoundryClientFactory = new TestCloudFoundryClientFactory(stubClient: client)
//    op.clusterProvider = clusterProvider
//
//    when:
//    cachingAgent.getAgentExecution(registry).executeAgent(cachingAgent)
//
//    op.operate([])
//
//    then:
//    CloudFoundryException e = thrown()
//    e.statusCode == HttpStatus.NOT_FOUND
//
//    task.history == [
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'INIT', status:'Creating task test', state:'STARTED')),
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'ENABLE_SERVER_GROUP', status:'Initializing enable server group operation for my-stack-v000 in test...', state:'STARTED')),
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'ENABLE_SERVER_GROUP', status:'Server group my-stack-v000 does not exist. Aborting enable operation.', state:'STARTED')),
//    ]
//
//    1 * client.getApplication(serverGroupName) >> { throw new CloudFoundryException(HttpStatus.NOT_FOUND, "Not Found", "Application not found") }
//    1 * client.spaces >> {
//      [
//          new CloudSpace(
//              mapToMeta([guid: uuid1, created: 1L]),
//              "test",
//              new CloudOrganization(
//                  mapToMeta([guid: uuid2, created: 2L]),
//                  "spinnaker"))
//      ]
//    }
//    1 * client.services >> { [new CloudService(mapToMeta([guid: uuid3, created: 3L]), 'spinnaker-redis')] }
//    1 * client.domainsForOrg >> { [new CloudDomain(null, 'cfapps.io', null)] }
//    1 * client.getRoutes('cfapps.io') >> {
//      [new CloudRoute(null, 'my-cool-test-app', new CloudDomain(null, 'cfapps.io', null), 1)]
//    }
//    1 * client.applications >> {
//      [
//          buildNativeApplication([
//              name     : 'testapp-production-v001',
//              state    : CloudApplication.AppState.STARTED.toString(),
//              instances: 1,
//              services : ['spinnaker-redis'],
//              memory   : 1024,
//              env      : ["${CloudFoundryConstants.LOAD_BALANCERS}=my-cool-test-app".toString()],
//              meta     : [
//                  guid   : uuid2,
//                  created: 5L
//              ],
//              space    : [
//                  meta        : [
//                      guid   : uuid3,
//                      created: 6L
//                  ],
//                  name        : 'test',
//                  organization: [
//                      meta: [
//                          guid   : uuid1,
//                          created: 7L
//                      ],
//                      name: 'spinnaker'
//                  ]
//              ]
//          ])
//      ]
//    }
//    1 * client.getApplicationInstances(_) >> {
//      new InstancesInfo([
//          [since: 1L, index: 0, state: InstanceState.RUNNING.toString()]
//      ])
//    }
//
//    0 * client._
//  }
//

    void "should enable standard server group"() {
      given:
      def serverGroupName = "my-stack-v000"

      def op = new EnableCloudFoundryServerGroupAtomicOperation(
          new EnableDisableCloudFoundryServerGroupDescription(
              serverGroupName: serverGroupName,
              region: "test",
              credentials: TestCredential.named('baz')))
      op.cloudFoundryClientFactory = cloudFoundryClientFactory
      op.clusterProvider = clusterProvider
      op.operationPoller = new OperationPoller(1,3)

      when:
      cachingAgent.getAgentExecution(registry).executeAgent(cachingAgent)

      op.operate([])

      then:
      1 * operations.spaces() >> { spaces }
      1 * operations.routes() >> { routes }
      1 * operations.services() >> { services }
      5 * operations.applications() >> { applications }
      0 * operations._

      2 * applications.list() >> {
        Flux.just(
          ApplicationSummary.builder()
            .name(serverGroupName)
            .build())
      }
      2 * applications.getEnvironments(GetApplicationEnvironmentsRequest.builder()
        .name(serverGroupName)
        .build()) >> {
          Mono.just(ApplicationEnvironments.builder()
            .userProvided([(CloudFoundryConstants.LOAD_BALANCERS): 'production,staging'])
            .build()) }
      1 * applications.get(GetApplicationRequest.builder()
        .name(serverGroupName)
        .build()) >> {
           Mono.just(ApplicationDetail.builder()
            .name(serverGroupName)
            .instances(2)
            .instanceDetails([InstanceDetail.builder()
              .state("STARTING")
              .build()])
            .build())
        }
      0 * applications._

      1 * spaces.get(GetSpaceRequest.builder().name('test').build()) >> {
        Mono.just(SpaceDetail.builder()
          .name('test')
          .build())
      }
      0 * spaces._

      1 * routes.list(_) >> { Flux.fromArray([])}
      0 * routes._

      1 * services.listInstances() >> { Flux.fromArray([ServiceInstance.builder()
        .name('spinnaker-redis')
        .application(serverGroupName)
        .build()]) }
      0 * services._

      1 * task.updateStatus('ENABLE_SERVER_GROUP', 'Initializing enable server group operation for my-stack-v000 in test...')
      0 * task._
//    1 * instancesInfo.instances >> { [new InstanceInfo([index: '0', state: InstanceState.STARTING.toString()])] }
//    1 * instancesInfo.instances >> { [new InstanceInfo([index: '0', state: InstanceState.RUNNING.toString()])] }
//    0 * instancesInfo._
//
//    1 * client.getApplication(serverGroupName) >> {
//      def app = new CloudApplication(null, serverGroupName)
//      app.env = [(CloudFoundryConstants.LOAD_BALANCERS): 'production,staging']
//      app.uris = ['other.cfapps.io']
//      app
//    }
//    1 * client.updateApplicationUris(serverGroupName, ['other.cfapps.io', 'production.cfapps.io', 'staging.cfapps.io'])
//    1 * client.startApplication(serverGroupName)
//    2 * client.getApplicationInstances(_) >> { instancesInfo }
//
//    task.history == [
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'INIT', status:'Creating task test', state:'STARTED')),
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'ENABLE_SERVER_GROUP', status:'Initializing enable server group operation for my-stack-v000 in test...', state:'STARTED')),
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'ENABLE_SERVER_GROUP', status:'Registering instances with load balancers...', state:'STARTED')),
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'ENABLE_SERVER_GROUP', status:'Starting server group my-stack-v000', state:'STARTED')),
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'ENABLE_SERVER_GROUP', status:'Done operating on my-stack-v000.', state:'STARTED')),
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'ENABLE_SERVER_GROUP', status:'Done enabling server group my-stack-v000 in test.', state:'STARTED')),
//    ]
//
//    1 * client.spaces >> {
//      [
//          new CloudSpace(
//              mapToMeta([guid: uuid1, created: 1L]),
//              "test",
//              new CloudOrganization(
//                  mapToMeta([guid: uuid2, created: 2L]),
//                  "spinnaker"))
//      ]
//    }
//    1 * client.services >> { [new CloudService(mapToMeta([guid: uuid3, created: 3L]), 'spinnaker-redis')] }
//    1 * client.domainsForOrg >> { [new CloudDomain(null, 'cfapps.io', null)] }
//    1 * client.getRoutes('cfapps.io') >> { [
//        new CloudRoute(null, 'other', new CloudDomain(null, 'cfapps.io', null), 1),
//        new CloudRoute(null, 'production', new CloudDomain(null, 'cfapps.io', null), 1),
//        new CloudRoute(null, 'staging', new CloudDomain(null, 'cfapps.io', null), 1)
//    ]}
//    1 * client.applications >> {
//      [
//          buildNativeApplication([
//              name     : serverGroupName,
//              state    : CloudApplication.AppState.STARTED.toString(),
//              instances: 1,
//              services : ['spinnaker-redis'],
//              memory   : 1024,
//              env      : ["${CloudFoundryConstants.LOAD_BALANCERS}=production,staging".toString()],
//              meta     : [
//                  guid   : uuid2,
//                  created: 5L
//              ],
//              space    : [
//                  meta        : [
//                      guid   : uuid3,
//                      created: 6L
//                  ],
//                  name        : 'test',
//                  organization: [
//                      meta: [
//                          guid   : uuid1,
//                          created: 7L
//                      ],
//                      name: 'spinnaker'
//                  ]
//              ]
//          ])
//      ]
//    }
//
//    0 * client._
  }
//
//  void "cannot enable server group with empty list of load balancers"() {
//    setup:
//    def serverGroupName = "my-stack-v000"
//    def op = new EnableCloudFoundryServerGroupAtomicOperation(
//        new EnableDisableCloudFoundryServerGroupDescription(
//            serverGroupName: serverGroupName,
//            region: "test",
//            credentials: TestCredential.named('baz')))
//    op.cloudFoundryClientFactory = new TestCloudFoundryClientFactory(stubClient: client)
//    op.clusterProvider = clusterProvider
//    op.operationPoller = new OperationPoller(1,3)
//
//    when:
//    cachingAgent.getAgentExecution(registry).executeAgent(cachingAgent)
//
//    op.operate([])
//
//    then:
//    1 * client.getApplication(serverGroupName) >> {
//      def app = new CloudApplication(null, serverGroupName)
//      app.env = [(CloudFoundryConstants.LOAD_BALANCERS): '']
//      app
//    }
//    1 * client.getApplicationInstances(_) >> { new InstancesInfo([])}
//
//    RuntimeException e = thrown()
//    e.message == "${serverGroupName} is not linked to any load balancers and can NOT be enabled"
//
//    task.history == [
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'INIT', status:'Creating task test', state:'STARTED')),
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'ENABLE_SERVER_GROUP', status:'Initializing enable server group operation for my-stack-v000 in test...', state:'STARTED')),
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'ENABLE_SERVER_GROUP', status:'my-stack-v000 is not linked to any load balancers and can NOT be enabled', state:'STARTED')),
//    ]
//
//    1 * client.spaces >> {
//      [
//          new CloudSpace(
//              mapToMeta([guid: uuid1, created: 1L]),
//              "test",
//              new CloudOrganization(
//                  mapToMeta([guid: uuid2, created: 2L]),
//                  "spinnaker"))
//      ]
//    }
//    1 * client.services >> { [new CloudService(mapToMeta([guid: uuid3, created: 3L]), 'spinnaker-redis')] }
//    1 * client.domainsForOrg >> { [new CloudDomain(null, 'cfapps.io', null)] }
//    1 * client.getRoutes('cfapps.io') >> { [
//        new CloudRoute(null, 'other', new CloudDomain(null, 'cfapps.io', null), 1),
//        new CloudRoute(null, 'production', new CloudDomain(null, 'cfapps.io', null), 1),
//        new CloudRoute(null, 'staging', new CloudDomain(null, 'cfapps.io', null), 1)
//    ]}
//    1 * client.applications >> {
//      [
//          buildNativeApplication([
//              name     : serverGroupName,
//              state    : CloudApplication.AppState.STARTED.toString(),
//              instances: 1,
//              services : ['spinnaker-redis'],
//              memory   : 1024,
//              env      : ["${CloudFoundryConstants.LOAD_BALANCERS}=production,staging".toString()],
//              meta     : [
//                  guid   : uuid2,
//                  created: 5L
//              ],
//              space    : [
//                  meta        : [
//                      guid   : uuid3,
//                      created: 6L
//                  ],
//                  name        : 'test',
//                  organization: [
//                      meta: [
//                          guid   : uuid1,
//                          created: 7L
//                      ],
//                      name: 'spinnaker'
//                  ]
//              ]
//          ])
//      ]
//    }
//
//    0 * client._
//  }
//
//  void "cannot enable server group with no list of load balancers"() {
//    setup:
//    def serverGroupName = "my-stack-v000"
//    def op = new EnableCloudFoundryServerGroupAtomicOperation(
//        new EnableDisableCloudFoundryServerGroupDescription(
//            serverGroupName: serverGroupName,
//            region: "test",
//            credentials: TestCredential.named('baz')))
//    op.cloudFoundryClientFactory = new TestCloudFoundryClientFactory(stubClient: client)
//    op.clusterProvider = clusterProvider
//    op.operationPoller = new OperationPoller(1,3)
//
//    when:
//    cachingAgent.getAgentExecution(registry).executeAgent(cachingAgent)
//
//    op.operate([])
//
//    then:
//    1 * client.getApplication(serverGroupName) >> { new CloudApplication(null, serverGroupName) }
//    1 * client.getApplicationInstances(_) >> { new InstancesInfo([])}
//
//    RuntimeException e = thrown()
//    e.message == "${serverGroupName} is not linked to any load balancers and can NOT be enabled"
//
//    task.history == [
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'INIT', status:'Creating task test', state:'STARTED')),
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'ENABLE_SERVER_GROUP', status:'Initializing enable server group operation for my-stack-v000 in test...', state:'STARTED')),
//        new TaskDisplayStatus(new DefaultTaskStatus(phase:'ENABLE_SERVER_GROUP', status:'my-stack-v000 is not linked to any load balancers and can NOT be enabled', state:'STARTED')),
//    ]
//
//    1 * client.spaces >> {
//      [
//          new CloudSpace(
//              mapToMeta([guid: uuid1, created: 1L]),
//              "test",
//              new CloudOrganization(
//                  mapToMeta([guid: uuid2, created: 2L]),
//                  "spinnaker"))
//      ]
//    }
//    1 * client.services >> { [new CloudService(mapToMeta([guid: uuid3, created: 3L]), 'spinnaker-redis')] }
//    1 * client.domainsForOrg >> { [new CloudDomain(null, 'cfapps.io', null)] }
//    1 * client.getRoutes('cfapps.io') >> { [
//        new CloudRoute(null, 'other', new CloudDomain(null, 'cfapps.io', null), 1),
//        new CloudRoute(null, 'production', new CloudDomain(null, 'cfapps.io', null), 1),
//        new CloudRoute(null, 'staging', new CloudDomain(null, 'cfapps.io', null), 1)
//    ]}
//    1 * client.applications >> {
//      [
//          buildNativeApplication([
//              name     : serverGroupName,
//              state    : CloudApplication.AppState.STARTED.toString(),
//              instances: 1,
//              services : ['spinnaker-redis'],
//              memory   : 1024,
//              env      : ["${CloudFoundryConstants.LOAD_BALANCERS}=production,staging".toString()],
//              meta     : [
//                  guid   : uuid2,
//                  created: 5L
//              ],
//              space    : [
//                  meta        : [
//                      guid   : uuid3,
//                      created: 6L
//                  ],
//                  name        : 'test',
//                  organization: [
//                      meta: [
//                          guid   : uuid1,
//                          created: 7L
//                      ],
//                      name: 'spinnaker'
//                  ]
//              ]
//          ])
//      ]
//    }
//
//    0 * client._
//  }

}
