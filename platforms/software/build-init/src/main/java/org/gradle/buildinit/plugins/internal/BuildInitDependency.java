/*
 * Copyright 2023 the original author or authors.
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

package org.gradle.buildinit.plugins.internal;

import org.gradle.api.NonNullApi;

import javax.annotation.Nullable;

/**
 * DAO for use with version catalog generation to encode module, version and if generated aliases should be shortened or qualified.
 */
@NonNullApi
public class BuildInitDependency {
    final String module;
    final String version;
    final boolean fullyQualifyAliases;
    private BuildInitDependency(String module, @Nullable String version, boolean fullyQualifyAliases) {
        this.module = module;
        this.version = version;
        this.fullyQualifyAliases = fullyQualifyAliases;
    }

    public static BuildInitDependency of(String module, String version) {
        return new BuildInitDependency(module, version, false);
    }

    public static BuildInitDependency of(String group, String artifact, String version) {
        return new BuildInitDependency(group + ":" + artifact, version, true);
    }

    public static BuildInitDependency of(String module) {
        return new BuildInitDependency(module, null, false);
    }

    public String toNotation() {
        return module + (version != null ? ":" + version : "");
    }

    public boolean isFullyQualifyAliases() {
        return fullyQualifyAliases;
    }
}
