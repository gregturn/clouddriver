package com.netflix.spinnaker.kato.aws.deploy.converters

import com.netflix.spinnaker.clouddriver.aws.AmazonOperation
import com.netflix.spinnaker.kato.aws.deploy.description.AttachClassicLinkVpcDescription
import com.netflix.spinnaker.kato.aws.deploy.ops.AttachClassicLinkVpcAtomicOperation
import com.netflix.spinnaker.kato.orchestration.AtomicOperation
import com.netflix.spinnaker.kato.orchestration.AtomicOperations
import com.netflix.spinnaker.kato.security.AbstractAtomicOperationsCredentialsSupport
import org.springframework.stereotype.Component

@AmazonOperation(AtomicOperations.ATTACH_CLASSIC_LINK_VPC)
@Component("attachClassicLinkVpcDescription")
class AttachClassicLinkVpcAtomicOperationConverter extends AbstractAtomicOperationsCredentialsSupport {
  @Override
  AtomicOperation convertOperation(Map input) {
    new AttachClassicLinkVpcAtomicOperation(convertDescription(input))
  }

  @Override
  AttachClassicLinkVpcDescription convertDescription(Map input) {
    def converted = objectMapper.convertValue(input, AttachClassicLinkVpcDescription)
    converted.credentials = getCredentialsObject(input.credentials as String)
    converted
  }
}
