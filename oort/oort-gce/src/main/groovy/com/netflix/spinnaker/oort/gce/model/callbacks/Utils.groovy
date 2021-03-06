/*
 * Copyright 2014 Google, Inc.
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

package com.netflix.spinnaker.oort.gce.model.callbacks

import com.google.api.services.compute.model.Instance
import com.google.api.services.compute.model.InstanceTemplate
import com.google.api.services.compute.model.Metadata
import com.netflix.spinnaker.oort.gce.model.GoogleApplication
import com.netflix.spinnaker.oort.gce.model.GoogleCluster
import org.springframework.util.ClassUtils

import java.text.SimpleDateFormat

class Utils {
  public static final String TARGET_POOL_NAME_PREFIX = "tp"
  public static final String SIMPLE_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSX"

  // TODO(duftler): This list should be configurable.
  public static final List<String> baseImageProjects = ["centos-cloud", "coreos-cloud", "debian-cloud", "google-containers",
                                                        "opensuse-cloud", "rhel-cloud", "suse-cloud", "ubuntu-os-cloud"]

  static long getTimeFromTimestamp(String timestamp) {
    if (timestamp) {
      return new SimpleDateFormat(SIMPLE_DATE_FORMAT).parse(timestamp).getTime()
    } else {
      return System.currentTimeMillis()
    }
  }

  static String getLocalName(String fullUrl) {
    int lastIndex = fullUrl.lastIndexOf('/')

    return lastIndex != -1 ? fullUrl.substring(lastIndex + 1) : fullUrl
  }

  // TODO(duftler): Consolidate this method with the same one from kato/GCEUtil and move to a common library.
  static Map<String, String> buildMapFromMetadata(Metadata metadata) {
    metadata.items?.collectEntries { Metadata.Items metadataItems ->
      [(metadataItems.key): metadataItems.value]
    }
  }

  // TODO(duftler): Consolidate this method with the same one from kato/GCEUtil and move to a common library.
  static List<String> deriveNetworkLoadBalancerNamesFromTargetPoolUrls(List<String> targetPoolUrls) {
    if (targetPoolUrls) {
      return targetPoolUrls.collect { targetPoolUrl ->
        def targetPoolLocalName = getLocalName(targetPoolUrl)

        targetPoolLocalName.split("-$TARGET_POOL_NAME_PREFIX-")[0]
      }
    } else {
      return []
    }
  }

  static GoogleCluster retrieveOrCreatePathToCluster(
    Map<String, GoogleApplication> tempAppMap, String accountName, String appName, String clusterName) {
    if (!tempAppMap[appName]) {
      tempAppMap[appName] = new GoogleApplication(name: appName)
    }

    if (!tempAppMap[appName].clusterNames[accountName]) {
      tempAppMap[appName].clusterNames[accountName] = new HashSet<String>()
      tempAppMap[appName].clusters[accountName] = new HashMap<String, GoogleCluster>()
    }

    if (!tempAppMap[appName].clusters[accountName][clusterName]) {
      tempAppMap[appName].clusters[accountName][clusterName] =
        new GoogleCluster(name: clusterName, accountName: accountName)
    }

    tempAppMap[appName].clusterNames[accountName] << clusterName

    return tempAppMap[appName].clusters[accountName][clusterName]
  }

  static Map<String, GoogleApplication> deepCopyApplicationMap(Map<String, GoogleApplication> originalAppMap) {
    Map copyMap = new HashMap<String, GoogleApplication>()

    originalAppMap.each { appNameKey, originalGoogleApplication ->
      copyMap[appNameKey] = GoogleApplication.newInstance(originalGoogleApplication)
    }

    return copyMap
  }

  static Object getImmutableCopy(def value) {
    def valueClass = value.getClass()

    if (ClassUtils.isPrimitiveOrWrapper(valueClass) || valueClass == String.class) {
      return value
    } else if (value instanceof Cloneable) {
      return value.clone()
    } else if (value) {
      return value.toString()
    } else {
      return null
    }
  }

  static String getNetworkNameFromInstance(Instance instance) {
    return getLocalName(instance?.networkInterfaces?.getAt(0)?.network)
  }

  static String getNetworkNameFromInstanceTemplate(InstanceTemplate instanceTemplate) {
    return getLocalName(instanceTemplate?.properties?.networkInterfaces?.getAt(0)?.network)
  }
}
