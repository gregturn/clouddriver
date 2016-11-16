/*
 * Copyright 2016 the original author or authors.
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

package com.netflix.spinnaker.clouddriver.cf.deploy.handlers
import com.netflix.spinnaker.clouddriver.cf.TestCredential
import com.netflix.spinnaker.clouddriver.cf.deploy.description.CloudFoundryDeployDescription
import spock.lang.Specification
import spock.lang.Subject
/**
 * @author Greg Turnquist
 */
class CloudFoundryDeployHandler2Spec extends Specification {

	@Subject
	CloudFoundryDeployHandler2 handler

	void "only handles CF deployment description type"() {
		given:
		handler = new CloudFoundryDeployHandler2();
		def description = new CloudFoundryDeployDescription(credentials: TestCredential.named('test'))

		expect:
		handler.handles description
	}



}