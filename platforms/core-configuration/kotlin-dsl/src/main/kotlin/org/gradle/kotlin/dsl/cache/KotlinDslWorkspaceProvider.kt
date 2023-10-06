/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.kotlin.dsl.cache

import org.gradle.api.internal.cache.CacheConfigurationsInternal
import org.gradle.api.internal.cache.StringInterner
import org.gradle.cache.FileLockManager
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory
import org.gradle.cache.scopes.GlobalScopedCacheBuilderFactory
import org.gradle.internal.execution.workspace.impl.DefaultImmutableWorkspaceProvider
import org.gradle.internal.file.FileAccessTimeJournal
import org.gradle.internal.hash.ClassLoaderHierarchyHasher
import java.io.Closeable


internal
class KotlinDslWorkspaceProvider(
    cacheBuilderFactory: GlobalScopedCacheBuilderFactory,
    fileAccessTimeJournal: FileAccessTimeJournal,
    inMemoryCacheDecoratorFactory: InMemoryCacheDecoratorFactory,
    stringInterner: StringInterner,
    classLoaderHasher: ClassLoaderHierarchyHasher,
    cacheConfigurations: CacheConfigurationsInternal,
    fileLockManager: FileLockManager

) : Closeable {

    val accessors = DefaultImmutableWorkspaceProvider.withBuiltInHistory(
        cacheBuilderFactory
            .createCacheBuilder("kotlin-dsl/accessors")
            .withDisplayName("kotlin-dsl/accessors"),
        cacheBuilderFactory
            .createCacheBuilder("kotlin-dsl/accessors/.executionHistory")
            .withDisplayName("kotlin-dsl/accessors/.executionHistory"),
        fileAccessTimeJournal,
        inMemoryCacheDecoratorFactory,
        stringInterner,
        classLoaderHasher,
        cacheConfigurations,
        fileLockManager
    )

    val scripts = DefaultImmutableWorkspaceProvider.withBuiltInHistory(
        cacheBuilderFactory
            .createCacheBuilder("kotlin-dsl/scripts")
            .withDisplayName("kotlin-dsl/scripts"),
        cacheBuilderFactory
            .createCacheBuilder("kotlin-dsl/scripts/.executionHistory")
            .withDisplayName("kotlin-dsl/scripts/.executionHistory"),
        fileAccessTimeJournal,
        inMemoryCacheDecoratorFactory,
        stringInterner,
        classLoaderHasher,
        cacheConfigurations,
        fileLockManager
    )

    override fun close() {
        accessors.close()
        scripts.close()
    }
}
