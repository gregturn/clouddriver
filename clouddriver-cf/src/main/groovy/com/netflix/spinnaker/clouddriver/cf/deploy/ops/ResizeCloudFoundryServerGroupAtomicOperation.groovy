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

import com.netflix.spinnaker.clouddriver.cf.deploy.description.ResizeCloudFoundryServerGroupDescription
import com.netflix.spinnaker.clouddriver.cf.utils.CloudFoundryClientFactory
import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation
import org.cloudfoundry.operations.applications.ScaleApplicationRequest
import org.springframework.beans.factory.annotation.Autowired

import java.time.Duration
import java.util.function.Function

class ResizeCloudFoundryServerGroupAtomicOperation implements AtomicOperation<Void> {

  private static final String BASE_PHASE = "RESIZE_SERVER_GROUP"

  @Autowired
  CloudFoundryClientFactory cloudFoundryClientFactory

  private final ResizeCloudFoundryServerGroupDescription description

  private static Task getTask() {
    TaskRepository.threadLocalTask.get()
  }

  ResizeCloudFoundryServerGroupAtomicOperation(ResizeCloudFoundryServerGroupDescription description) {
    this.description = description
  }

  @Override
  Void operate(List priorOutputs) {
    task.updateStatus BASE_PHASE, "Initializing resize of server group $description.serverGroupName in $description.region..."

    def operations = cloudFoundryClientFactory.createCloudFoundryOperations(description.credentials, true)

    operations.applications()
      .scale(ScaleApplicationRequest.builder()
        .name(description.serverGroupName)
        .instances(description.targetSize)
        .diskLimit(description.disk)
        .memoryLimit(description.memory)
        .build())
      .then({
        task.updateStatus BASE_PHASE, "Done resizing server group $description.serverGroupName in $description.region."
      } as Function)
      .otherwise({e ->
        task.updateStatus BASE_PHASE, "Failed to resize server group $description.serverGroupName in $description.region."
      })
      .block(Duration.ofMinutes(2))

    null
  }
}
