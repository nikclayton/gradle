/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.api.plugins.jvm.internal;

import com.google.common.collect.Maps;
import org.gradle.api.Action;
import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.internal.artifacts.dsl.dependencies.DefaultDependencyAdder;
import org.gradle.api.internal.tasks.JvmConstants;
import org.gradle.api.internal.tasks.TaskDependencyFactory;
import org.gradle.api.internal.tasks.testing.TestFramework;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JvmTestSuitePlugin;
import org.gradle.api.plugins.jvm.JvmComponentDependencies;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.api.plugins.jvm.JvmTestSuiteTarget;
import org.gradle.api.plugins.jvm.internal.testing.toolchains.JUnit4TestToolchain;
import org.gradle.api.plugins.jvm.internal.testing.toolchains.JUnitJupiterToolchain;
import org.gradle.api.plugins.jvm.internal.testing.toolchains.JVMTestToolchain;
import org.gradle.api.plugins.jvm.internal.testing.toolchains.KotlinTestToolchain;
import org.gradle.api.plugins.jvm.internal.testing.toolchains.LegacyJUnit4TestToolchain;
import org.gradle.api.plugins.jvm.internal.testing.toolchains.SpockToolchain;
import org.gradle.api.plugins.jvm.internal.testing.toolchains.TestNGToolchain;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.api.tasks.testing.Test;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.isolated.IsolationScheme;
import org.gradle.internal.service.ServiceLookup;
import org.gradle.internal.service.ServiceRegistry;

import javax.inject.Inject;
import java.util.Collections;
import java.util.Map;

import static org.gradle.internal.Cast.uncheckedCast;

public abstract class DefaultJvmTestSuite implements JvmTestSuite {
    private final ExtensiblePolymorphicDomainObjectContainer<JvmTestSuiteTarget> targets;
    private final SourceSet sourceSet;
    private final String name;
    private final JvmComponentDependencies dependencies;
    private final TaskDependencyFactory taskDependencyFactory;
    private final ToolchainCache toolchainCache = new ToolchainCache();

    @Inject
    public DefaultJvmTestSuite(String name, SourceSetContainer sourceSets, ConfigurationContainer configurations, TaskDependencyFactory taskDependencyFactory) {
        this.name = name;
        this.sourceSet = sourceSets.create(getName());
        this.taskDependencyFactory = taskDependencyFactory;

        Configuration compileOnly = configurations.getByName(sourceSet.getCompileOnlyConfigurationName());
        Configuration implementation = configurations.getByName(sourceSet.getImplementationConfigurationName());
        Configuration runtimeOnly = configurations.getByName(sourceSet.getRuntimeOnlyConfigurationName());
        Configuration annotationProcessor = configurations.getByName(sourceSet.getAnnotationProcessorConfigurationName());

        this.targets = getObjectFactory().polymorphicDomainObjectContainer(JvmTestSuiteTarget.class);
        this.targets.registerBinding(JvmTestSuiteTarget.class, DefaultJvmTestSuiteTarget.class);

        this.dependencies = getObjectFactory().newInstance(
                DefaultJvmComponentDependencies.class,
                getObjectFactory().newInstance(DefaultDependencyAdder.class, implementation),
                getObjectFactory().newInstance(DefaultDependencyAdder.class, compileOnly),
                getObjectFactory().newInstance(DefaultDependencyAdder.class, runtimeOnly),
                getObjectFactory().newInstance(DefaultDependencyAdder.class, annotationProcessor)
        );

        if (name.equals(JvmTestSuitePlugin.DEFAULT_TEST_SUITE_NAME)) {
            // for the built-in test suite, we don't express an opinion, so we will not add any dependencies
            // if a user explicitly calls useJUnit or useJUnitJupiter, the built-in test suite will behave like a custom one
            // and add dependencies automatically.
            LegacyJUnit4TestToolchain toolchain = toolchainCache.create(LegacyJUnit4TestToolchain.class);
            getTestToolchain().convention(toolchain);
        } else {
            JUnitJupiterToolchain toolchain = toolchainCache.create(JUnitJupiterToolchain.class);
            getTestToolchain().convention(toolchain);
            toolchain.getParameters().getJupiterVersion().convention(JUnitJupiterToolchain.DEFAULT_VERSION);
        }

        addDefaultTestTarget();

        this.targets.withType(JvmTestSuiteTarget.class).configureEach(target -> {
            target.getTestTask().configure(task -> {
                initializeTestFramework(name, task);
            });
        });

        // This is a workaround for strange behavior from the Kotlin plugin
        //
        // The Kotlin plugin attempts to look at the declared dependencies to know if it needs to add its own dependencies.
        // We avoid triggering realization of getTestSuiteTestingFramework by only adding our dependencies just before
        // resolution.
        implementation.withDependencies(dependencySet ->
            dependencySet.addAllLater(getTestToolchain().map(JVMTestToolchain::getImplementationDependencies)
                .orElse(Collections.emptyList()))
        );
        runtimeOnly.withDependencies(dependencySet ->
            dependencySet.addAllLater(getTestToolchain().map(JVMTestToolchain::getRuntimeOnlyDependencies)
                .orElse(Collections.emptyList()))
        );
        compileOnly.withDependencies(dependenciesSet ->
            dependenciesSet.addAllLater(getTestToolchain().map(JVMTestToolchain::getCompileOnlyDependencies)
                .orElse(Collections.emptyList()))
        );
    }

    private void initializeTestFramework(String name, Test task) {
        Provider<TestFramework> mapTestingFrameworkToTestFramework = getTestToolchain().map(toolchain -> toolchain.createTestFramework(task));
        // The Test task's testing framework is derived from the test suite's toolchain
        task.getTestFrameworkProperty().convention(mapTestingFrameworkToTestFramework);
    }

    private void addDefaultTestTarget() {
        final String target;
        if (getName().equals(JvmTestSuitePlugin.DEFAULT_TEST_SUITE_NAME)) {
            target = JvmConstants.TEST_TASK_NAME;
        } else {
            target = getName(); // For now, we'll just name the test task for the single target for the suite with the suite name
        }

        targets.register(target);
    }

    protected abstract Property<JVMTestToolchain<?>> getTestToolchain();

    @Override
    public String getName() {
        return name;
    }

    @Inject
    public abstract ObjectFactory getObjectFactory();

    @Inject
    public abstract ProviderFactory getProviderFactory();

    @Inject
    public abstract InstantiatorFactory getInstantiatorFactory();

    @Inject
    public abstract ServiceRegistry getParentServices();

    @Override
    public SourceSet getSources() {
        return sourceSet;
    }

    @Override
    public void sources(Action<? super SourceSet> configuration) {
        configuration.execute(getSources());
    }

    @Override
    public ExtensiblePolymorphicDomainObjectContainer<JvmTestSuiteTarget> getTargets() {
        return targets;
    }

    @Override
    public void useJUnit() {
        useJUnit(JUnit4TestToolchain.DEFAULT_VERSION);
    }

    @Override
    public void useJUnit(String version) {
        useJUnit(getProviderFactory().provider(() -> version));
    }

    @Override
    public void useJUnit(Provider<String> version) {
        useJUnit(parameters -> parameters.getVersion().set(version));
    }

    private void useJUnit(Action<JUnit4TestToolchain.Parameters> action) {
        setToolchainAndConfigure(JUnit4TestToolchain.class, action);
    }

    @Override
    public void useJUnitJupiter() {
        useJUnitJupiter(JUnitJupiterToolchain.DEFAULT_VERSION);
    }

    @Override
    public void useJUnitJupiter(String version) {
        useJUnitJupiter(getProviderFactory().provider(() -> version));
    }

    @Override
    public void useJUnitJupiter(Provider<String> version) {
        useJUnitJupiter(parameters -> parameters.getJupiterVersion().set(version));
    }

    private void useJUnitJupiter(Action<JUnitJupiterToolchain.Parameters> action) {
        setToolchainAndConfigure(JUnitJupiterToolchain.class, action);
    }

    @Override
    public void useSpock() {
        useSpock(SpockToolchain.DEFAULT_VERSION);
    }

    @Override
    public void useSpock(String version) {
        useSpock(getProviderFactory().provider(() -> version));
    }

    @Override
    public void useSpock(Provider<String> version) {
        useSpock(parameters -> parameters.getSpockVersion().set(version));
    }

    private void useSpock(Action<SpockToolchain.Parameters> action) {
        setToolchainAndConfigure(SpockToolchain.class, action);
    }

    @Override
    public void useKotlinTest() {
        useKotlinTest(KotlinTestToolchain.DEFAULT_VERSION);
    }

    @Override
    public void useKotlinTest(String version) {
        useKotlinTest(getProviderFactory().provider(() -> version));
    }

    @Override
    public void useKotlinTest(Provider<String> version) {
        useKotlinTest(parameters -> parameters.getKotlinTestVersion().set(version));
    }

    private void useKotlinTest(Action<KotlinTestToolchain.Parameters> action) {
        setToolchainAndConfigure(KotlinTestToolchain.class, action);
    }

    @Override
    public void useTestNG() {
        useTestNG(TestNGToolchain.DEFAULT_VERSION);
    }

    @Override
    public void useTestNG(String version) {
        useTestNG(getProviderFactory().provider(() -> version));
    }

    @Override
    public void useTestNG(Provider<String> version) {
        useTestNG(parameters -> parameters.getVersion().set(version));
    }

    private void useTestNG(Action<TestNGToolchain.Parameters> action) {
        setToolchainAndConfigure(TestNGToolchain.class, action);
    }

    private <T extends JVMTestToolchain.Parameters> void setToolchainAndConfigure(Class<? extends JVMTestToolchain<T>> toolchainType, Action<T> action) {
        JVMTestToolchain<T> toolchain = toolchainCache.create(toolchainType);
        getTestToolchain().set(toolchain);
        action.execute(toolchain.getParameters());
    }

    @Override
    public JvmComponentDependencies getDependencies() {
        return dependencies;
    }

    @Override
    public void dependencies(Action<? super JvmComponentDependencies> action) {
        action.execute(dependencies);
    }

    @Override
    public TaskDependency getBuildDependencies() {
        return taskDependencyFactory.visitingDependencies(context -> {
            getTargets().forEach(context::add);
        });
    }

    private class ToolchainCache {
        private final Map<Class<? extends JVMTestToolchain<?>>, JVMTestToolchain<?>> cache = Maps.newHashMap();

        public <T extends JVMTestToolchain<?>> T create(Class<T> type) {
            return uncheckedCast(cache.computeIfAbsent(type, t -> newToolchain(uncheckedCast(type))));
        }

        private <T extends JVMTestToolchain.Parameters> JVMTestToolchain<T> newToolchain(Class<? extends JVMTestToolchain<T>> type) {
            IsolationScheme<JVMTestToolchain<?>, JVMTestToolchain.Parameters> isolationScheme = new IsolationScheme<>(uncheckedCast(JVMTestToolchain.class), JVMTestToolchain.Parameters.class, JVMTestToolchain.Parameters.None.class);
            Class<T> parametersType = isolationScheme.parameterTypeFor(type);
            T parameters = parametersType == null ? null : getObjectFactory().newInstance(parametersType);
            ServiceLookup lookup = isolationScheme.servicesForImplementation(parameters, getParentServices(), Collections.emptyList(), p -> true);
            return getInstantiatorFactory().decorate(lookup).newInstance(type);
        }
    }
}
