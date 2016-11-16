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

package com.netflix.spinnaker.clouddriver.cf.model

import com.netflix.spinnaker.clouddriver.model.HealthState
import com.netflix.spinnaker.clouddriver.model.Instance
import groovy.transform.EqualsAndHashCode
import org.cloudfoundry.operations.applications.InstanceDetail

@EqualsAndHashCode(includes = ["name"])
class CloudFoundryApplicationInstance implements Instance, Serializable {

  String name
  HealthState healthState
  List<Map<String, String>> health = []
  CloudFoundryServerGroup serverGroup
  Collection<CloudFoundryLoadBalancer> loadBalancers = []
  InstanceDetail nativeInstance
  String space
  String consoleLink
  String logsLink

  @Override
  Long getLaunchTime() {
    nativeInstance.since.time
  }

  @Override
  String getZone() {
    space
  }

  static HealthState instanceStateToHealthState(String instanceState) {

    switch (instanceState) {
      case "DOWN":
        return HealthState.Down
      case "STARTING":
        return HealthState.Starting
      case "RUNNING":
        return HealthState.Up
      case "CRASHED":
        return HealthState.Down
      case "FLAPPING":
        return HealthState.Down
      case "UNKNOWN":
        return HealthState.Unknown
    }
  }

  static List<Map<String, String>> createInstanceHealth(CloudFoundryApplicationInstance instance) {
    [[
      state      : instance.healthState.toString(),
      zone       : instance.zone,
      type       : 'serverGroup',
      description: 'Is this CF server group running?'
    ]]
  }

}
