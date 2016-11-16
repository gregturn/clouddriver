/*
 * Copyright 2015-2016 Pivotal Inc.
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

import com.netflix.spinnaker.clouddriver.cf.TestCredential
import com.netflix.spinnaker.clouddriver.cf.deploy.description.DestroyCloudFoundryServerGroupDescription
import com.netflix.spinnaker.clouddriver.cf.security.TestCloudFoundryClientFactory
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import org.cloudfoundry.operations.CloudFoundryOperations
import org.cloudfoundry.operations.applications.Applications
import org.cloudfoundry.operations.applications.DeleteApplicationRequest
import reactor.core.publisher.Mono
import spock.lang.Ignore
import spock.lang.Specification

@Ignore
class DestroyCloudFoundryServerGroupAtomicOperationSpec extends Specification {

  void "should not fail delete when server group does not exist"() {
    given:
    def task = Mock(Task)
    TaskRepository.threadLocalTask.set(task)

    def operations = Mock(CloudFoundryOperations)
    def applications = Mock(Applications)

    def op = new DestroyCloudFoundryServerGroupAtomicOperation(
        new DestroyCloudFoundryServerGroupDescription(
            serverGroupName: "my-stack-v000",
            region: "staging",
            credentials: TestCredential.named('baz')))
    op.cloudFoundryClientFactory = new TestCloudFoundryClientFactory(stubOperations: operations)

    when:
    op.operate([])

    then:
    //notThrown(Exception) // TODO: Reinstate proper behavior where an Exception is NOT propagated to this level
    thrown(Exception)

    1 * operations.applications() >> { applications }
    0 * operations._

    1 * applications.delete(DeleteApplicationRequest.builder().name("my-stack-v000").deleteRoutes(true).build()) >> { throw new RuntimeException("Failure!") }
    0 * applications._

    1 * task.updateStatus('DESTROY_SERVER_GROUP', 'Initializing destruction of server group my-stack-v000 in staging...')
    0 * task._
  }

  void "should delete server group"() {
    given:
    def task = Mock(Task)
    TaskRepository.threadLocalTask.set(task)

    def operations = Mock(CloudFoundryOperations)
    def applications = Mock(Applications)

    def op = new DestroyCloudFoundryServerGroupAtomicOperation(
        new DestroyCloudFoundryServerGroupDescription(
            serverGroupName: "my-stack-v000",
            region: "staging",
            credentials: TestCredential.named('baz')))
    op.cloudFoundryClientFactory = new TestCloudFoundryClientFactory(stubOperations: operations)

    when:
    op.operate([])

    then:
    1 * operations.applications() >> { applications }
    0 * operations._

    1 * applications.delete(DeleteApplicationRequest.builder()
        .name("my-stack-v000")
        .deleteRoutes(true)
        .build()) >> { Mono.empty() }
    0 * applications._

    1 * task.updateStatus('DESTROY_SERVER_GROUP', 'Initializing destruction of server group my-stack-v000 in staging...')
    1 * task.updateStatus('DESTROY_SERVER_GROUP', 'Done destroying server group my-stack-v000 in staging.')
    0 * task._
  }

}
