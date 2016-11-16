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
import com.netflix.spinnaker.clouddriver.cf.deploy.description.DestroyCloudFoundryServerGroupDescription
import com.netflix.spinnaker.clouddriver.cf.utils.CloudFoundryClientFactory
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation
import org.cloudfoundry.operations.applications.DeleteApplicationRequest
import org.springframework.beans.factory.annotation.Autowired

import java.time.Duration
import java.util.function.Function

class DestroyCloudFoundryServerGroupAtomicOperation implements AtomicOperation<Void> {

  private static final String BASE_PHASE = "DESTROY_SERVER_GROUP"

  @Autowired
  CloudFoundryClientFactory cloudFoundryClientFactory

  @Autowired
  TaskRepository taskRepository

  DestroyCloudFoundryServerGroupDescription description

  DestroyCloudFoundryServerGroupAtomicOperation(DestroyCloudFoundryServerGroupDescription description) {
    this.description = description
  }

  @Override
  Void operate(List priorOutputs) {

    def task = taskRepository.create(BASE_PHASE, "Initializing destruction of server group $description.serverGroupName in $description.region...")
    TaskRepository.threadLocalTask.set(task)

    def operations = cloudFoundryClientFactory.createCloudFoundryOperations(description.credentials, true)

    operations.applications()
      .delete(DeleteApplicationRequest.builder()
        .name(description.serverGroupName)
        .deleteRoutes(true)
        .build())
      .then({
        task.updateStatus BASE_PHASE, "Done destroying server group $description.serverGroupName in $description.region."
      } as Function)
      .otherwise({e ->
        task.updateStatus BASE_PHASE, "Failed to delete server group $description.serverGroupName => $e.message"
      })
      .block(Duration.ofMinutes(5))

    null
  }
}
