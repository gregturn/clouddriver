/*
 * Copyright 2016 Pivotal, Inc.
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

package com.netflix.spinnaker.clouddriver.cf.provider

import com.netflix.spinnaker.cats.cache.Cache
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.cache.CacheFilter
import org.cloudfoundry.client.lib.domain.*

class ProviderUtils {

  static Collection<CacheData> resolveRelationshipDataForCollection(Cache cacheView, Collection<CacheData> sources, String relationship, CacheFilter cacheFilter = null) {
    Collection<String> relationships = sources?.findResults { it.relationships[relationship]?: [] }?.flatten() ?: []
    relationships ? cacheView.getAll(relationship, relationships, cacheFilter) : []
  }

  static Collection<CacheData> resolveRelationshipData(Cache cacheView, CacheData source, String relationship) {
    resolveRelationshipData(cacheView, source, relationship) { true }
  }

  static Collection<CacheData> resolveRelationshipData(Cache cacheView, CacheData source, String relationship, Closure<Boolean> relFilter) {
    Collection<String> filteredRelationships = source.relationships[relationship]?.findAll(relFilter)
    filteredRelationships ? cacheView.getAll(relationship, filteredRelationships) : []
  }


}
