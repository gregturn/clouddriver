/*
 * Copyright 2015 Google, Inc.
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

package com.netflix.spinnaker.kato.gce.deploy.converters

import com.netflix.spinnaker.clouddriver.google.GoogleOperation
import com.netflix.spinnaker.kato.gce.deploy.description.DeleteGoogleSecurityGroupDescription
import com.netflix.spinnaker.kato.gce.deploy.ops.DeleteGoogleSecurityGroupAtomicOperation
import com.netflix.spinnaker.kato.orchestration.AtomicOperation
import com.netflix.spinnaker.kato.orchestration.AtomicOperations
import com.netflix.spinnaker.kato.security.AbstractAtomicOperationsCredentialsSupport
import org.springframework.stereotype.Component

@GoogleOperation(AtomicOperations.DELETE_SECURITY_GROUP)
@Component("deleteGoogleSecurityGroupDescription")
class DeleteGoogleSecurityGroupAtomicOperationConverter extends AbstractAtomicOperationsCredentialsSupport {
  @Override
  AtomicOperation convertOperation(Map input) {
    new DeleteGoogleSecurityGroupAtomicOperation(convertDescription(input))
  }

  @Override
  DeleteGoogleSecurityGroupDescription convertDescription(Map input) {
    GoogleAtomicOperationConverterHelper.convertDescription(input, this, DeleteGoogleSecurityGroupDescription)
  }
}
