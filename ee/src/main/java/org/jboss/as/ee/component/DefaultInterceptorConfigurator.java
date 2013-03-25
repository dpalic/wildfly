package org.jboss.as.ee.component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.as.ee.component.interceptors.InterceptorClassDescription;
import org.jboss.as.ee.component.interceptors.InterceptorOrder;
import org.jboss.as.ee.component.interceptors.UserInterceptorFactory;
import org.jboss.as.ee.metadata.MetadataCompleteMarker;
import org.jboss.as.naming.ValueManagedReferenceFactory;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.reflect.ClassIndex;
import org.jboss.as.server.deployment.reflect.ClassReflectionIndex;
import org.jboss.as.server.deployment.reflect.ClassReflectionIndexUtil;
import org.jboss.as.server.deployment.reflect.DeploymentClassIndex;
import org.jboss.as.server.deployment.reflect.DeploymentReflectionIndex;
import org.jboss.invocation.InterceptorFactory;
import org.jboss.invocation.Interceptors;
import org.jboss.invocation.proxy.MethodIdentifier;
import org.jboss.msc.value.ConstructedValue;
import org.jboss.msc.value.Value;

import static org.jboss.as.ee.EeMessages.MESSAGES;
import static org.jboss.as.server.deployment.Attachments.REFLECTION_INDEX;

/**
* @author Stuart Douglas
*/
class DefaultInterceptorConfigurator extends AbstractComponentConfigurator implements ComponentConfigurator {

    private static final Class[] EMPTY_CLASS_ARRAY = new Class[0];

    public void configure(final DeploymentPhaseContext context, final ComponentDescription description, final ComponentConfiguration configuration) throws DeploymentUnitProcessingException {
        final DeploymentUnit deploymentUnit = context.getDeploymentUnit();
        final DeploymentReflectionIndex deploymentReflectionIndex = deploymentUnit.getAttachment(REFLECTION_INDEX);
        final EEApplicationClasses applicationClasses = deploymentUnit.getAttachment(Attachments.EE_APPLICATION_CLASSES_DESCRIPTION);
        final EEModuleDescription moduleDescription = deploymentUnit.getAttachment(Attachments.EE_MODULE_DESCRIPTION);
        final DeploymentClassIndex classIndex = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.CLASS_INDEX);

        final boolean metadataComplete = MetadataCompleteMarker.isMetadataComplete(deploymentUnit);

        // Module stuff

        final Deque<InterceptorFactory> instantiators = new ArrayDeque<>();
        final Deque<InterceptorFactory> injectors = new ArrayDeque<>();
        final Deque<InterceptorFactory> uninjectors = new ArrayDeque<>();
        final Deque<InterceptorFactory> destructors = new ArrayDeque<>();


        final Map<String, List<InterceptorFactory>> userAroundInvokesByInterceptorClass = new HashMap<>();
        final Map<String, List<InterceptorFactory>> userAroundConstructsByInterceptorClass = new HashMap<String, List<InterceptorFactory>>();
        final Map<String, List<InterceptorFactory>> userAroundTimeoutsByInterceptorClass;
        final Map<String, List<InterceptorFactory>> userPrePassivatesByInterceptorClass;
        final Map<String, List<InterceptorFactory>> userPostActivatesByInterceptorClass;

        final Map<String, List<InterceptorFactory>> userPostConstructByInterceptorClass = new HashMap<String, List<InterceptorFactory>>();
        final Map<String, List<InterceptorFactory>> userPreDestroyByInterceptorClass = new HashMap<String, List<InterceptorFactory>>();

        final Set<MethodIdentifier> timeoutMethods = description.getTimerMethods();
        if (description.isTimerServiceRequired()) {
            userAroundTimeoutsByInterceptorClass = new HashMap<>();
        } else {
            userAroundTimeoutsByInterceptorClass = null;
        }

        if (description.isPassivationApplicable()) {
            userPrePassivatesByInterceptorClass = new HashMap<>();
            userPostActivatesByInterceptorClass = new HashMap<>();
        } else {
            userPrePassivatesByInterceptorClass = null;
            userPostActivatesByInterceptorClass = null;
        }


        //all interceptors with lifecycle callbacks, in the correct order
        final List<InterceptorDescription> interceptorWithLifecycleCallbacks = new ArrayList<InterceptorDescription>();
        if (!description.isExcludeDefaultInterceptors()) {
            interceptorWithLifecycleCallbacks.addAll(description.getDefaultInterceptors());
        }
        interceptorWithLifecycleCallbacks.addAll(description.getClassInterceptors());

        for (final InterceptorDescription interceptorDescription : description.getAllInterceptors()) {
            final String interceptorClassName = interceptorDescription.getInterceptorClassName();
            final ClassIndex interceptorClass;
            try {
                interceptorClass = classIndex.classIndex(interceptorClassName);
            } catch (ClassNotFoundException e) {
                throw MESSAGES.cannotLoadInterceptor(e, interceptorClassName);
            }

            final InterceptorEnvironment interceptorEnvironment = moduleDescription.getInterceptorEnvironment().get(interceptorClassName);
            if (interceptorEnvironment != null) {
                //if the interceptor has environment config we merge it into the components environment
                description.getBindingConfigurations().addAll(interceptorEnvironment.getBindingConfigurations());
                for (final ResourceInjectionConfiguration injection : interceptorEnvironment.getResourceInjections()) {
                    description.addResourceInjection(injection);
                }
            }


            //we store the interceptor instance under the class key
            final Object contextKey = interceptorClass.getModuleClass();
            configuration.getInterceptorContextKeys().add(contextKey);

            final ClassReflectionIndex<?> interceptorIndex = deploymentReflectionIndex.getClassIndex(interceptorClass.getModuleClass());
            final Constructor<?> constructor = interceptorIndex.getConstructor(EMPTY_CLASS_ARRAY);
            if (constructor == null) {
                throw MESSAGES.defaultConstructorNotFoundOnComponent(interceptorClassName, configuration.getComponentClass());
            }

            instantiators.addFirst(new ManagedReferenceInterceptorFactory(new ValueManagedReferenceFactory(new ConstructedValue(constructor, Collections.<Value<?>>emptyList())), contextKey));
            destructors.addLast(new ManagedReferenceReleaseInterceptorFactory(contextKey));

            final boolean interceptorHasLifecycleCallbacks = interceptorWithLifecycleCallbacks.contains(interceptorDescription);

            new ClassDescriptionTraversal(interceptorClass.getModuleClass(), applicationClasses) {
                @Override
                public void handle(final Class<?> clazz, EEModuleClassDescription classDescription) throws DeploymentUnitProcessingException {
                    mergeInjectionsForClass(clazz, interceptorClass.getModuleClass(), classDescription, moduleDescription, deploymentReflectionIndex, description, configuration, context, injectors, contextKey, uninjectors, metadataComplete);
                    final InterceptorClassDescription interceptorConfig;
                    if (classDescription != null && !metadataComplete) {
                        interceptorConfig = InterceptorClassDescription.merge(classDescription.getInterceptorClassDescription(), moduleDescription.getInterceptorClassOverride(clazz.getName()));
                    } else {
                        interceptorConfig = InterceptorClassDescription.merge(null, moduleDescription.getInterceptorClassOverride(clazz.getName()));
                    }

                    // Only class level interceptors are processed for postconstruct/predestroy methods.
                    // Method level interceptors aren't supposed to be processed for postconstruct/predestroy lifecycle
                    // methods, as per interceptors spec
                    if (interceptorHasLifecycleCallbacks) {
                        final MethodIdentifier postConstructMethodIdentifier = interceptorConfig.getPostConstruct();
                        handleInterceptorClass(clazz, postConstructMethodIdentifier, userPostConstructByInterceptorClass, true, true);
                        final MethodIdentifier preDestroyMethodIdentifier = interceptorConfig.getPreDestroy();
                        handleInterceptorClass(clazz, preDestroyMethodIdentifier, userPreDestroyByInterceptorClass, true, true);
                    }
                    final MethodIdentifier aroundInvokeMethodIdentifier = interceptorConfig.getAroundInvoke();
                    handleInterceptorClass(clazz, aroundInvokeMethodIdentifier, userAroundInvokesByInterceptorClass, false, false);

                    if (description.isTimerServiceRequired()) {
                        final MethodIdentifier aroundTimeoutMethodIdentifier = interceptorConfig.getAroundTimeout();
                        handleInterceptorClass(clazz, aroundTimeoutMethodIdentifier, userAroundTimeoutsByInterceptorClass, false, false);
                    }

                    if (description.isPassivationApplicable()) {
                        handleInterceptorClass(clazz, interceptorConfig.getPrePassivate(), userPrePassivatesByInterceptorClass, false, false);
                        handleInterceptorClass(clazz, interceptorConfig.getPostActivate(), userPostActivatesByInterceptorClass, false, false);
                    }

                }

                private void handleInterceptorClass(final Class<?> clazz, final MethodIdentifier methodIdentifier, final Map<String, List<InterceptorFactory>> classMap, final boolean changeMethod, final boolean lifecycleMethod) throws DeploymentUnitProcessingException {
                    if (methodIdentifier != null) {
                        final Method method = ClassReflectionIndexUtil.findRequiredMethod(deploymentReflectionIndex, clazz, methodIdentifier);
                        if (isNotOverriden(clazz, method, interceptorClass.getModuleClass(), deploymentReflectionIndex)) {
                            final InterceptorFactory interceptorFactory = new ManagedReferenceLifecycleMethodInterceptorFactory(contextKey, method, changeMethod, lifecycleMethod);
                            List<InterceptorFactory> factories = classMap.get(interceptorClassName);
                            if (factories == null) {
                                classMap.put(interceptorClassName, factories = new ArrayList<InterceptorFactory>());
                            }
                            factories.add(interceptorFactory);
                        }
                    }
                }
            }.run();
        }

        final List<InterceptorFactory> userPostConstruct = new ArrayList<InterceptorFactory>();
        final List<InterceptorFactory> userPreDestroy = new ArrayList<InterceptorFactory>();
        final List<InterceptorFactory> userPrePassivate = new ArrayList<InterceptorFactory>();
        final List<InterceptorFactory> userPostActivate = new ArrayList<InterceptorFactory>();

        //now add the lifecycle interceptors in the correct order
        for (final InterceptorDescription interceptorClass : interceptorWithLifecycleCallbacks) {
            if (userPostConstructByInterceptorClass.containsKey(interceptorClass.getInterceptorClassName())) {
                userPostConstruct.addAll(userPostConstructByInterceptorClass.get(interceptorClass.getInterceptorClassName()));
            }
            if (userPreDestroyByInterceptorClass.containsKey(interceptorClass.getInterceptorClassName())) {
                userPreDestroy.addAll(userPreDestroyByInterceptorClass.get(interceptorClass.getInterceptorClassName()));
            }
            if (description.isPassivationApplicable()) {
                if (userPrePassivatesByInterceptorClass.containsKey(interceptorClass.getInterceptorClassName())) {
                    userPrePassivate.addAll(userPrePassivatesByInterceptorClass.get(interceptorClass.getInterceptorClassName()));
                }
                if (userPostActivatesByInterceptorClass.containsKey(interceptorClass.getInterceptorClassName())) {
                    userPostActivate.addAll(userPostActivatesByInterceptorClass.get(interceptorClass.getInterceptorClassName()));
                }
            }
        }

        // Apply post-construct

        if (!injectors.isEmpty()) {
            configuration.addPostConstructInterceptor(weaved(injectors), InterceptorOrder.ComponentPostConstruct.INTERCEPTOR_RESOURCE_INJECTION_INTERCEPTORS);
        }
        if (!instantiators.isEmpty()) {
            configuration.addPostConstructInterceptor(weaved(instantiators), InterceptorOrder.ComponentPostConstruct.INTERCEPTOR_INSTANTIATION_INTERCEPTORS);
        }
        if (!userPostConstruct.isEmpty()) {
            configuration.addPostConstructInterceptor(weaved(userPostConstruct), InterceptorOrder.ComponentPostConstruct.INTERCEPTOR_USER_INTERCEPTORS);
        }
        configuration.addPostConstructInterceptor(Interceptors.getTerminalInterceptorFactory(), InterceptorOrder.ComponentPostConstruct.TERMINAL_INTERCEPTOR);

        // Apply pre-destroy
        if (!uninjectors.isEmpty()) {
            configuration.addPreDestroyInterceptor(weaved(uninjectors), InterceptorOrder.ComponentPreDestroy.INTERCEPTOR_UNINJECTION_INTERCEPTORS);
        }
        if (!destructors.isEmpty()) {
            configuration.addPreDestroyInterceptor(weaved(destructors), InterceptorOrder.ComponentPreDestroy.INTERCEPTOR_DESTRUCTION_INTERCEPTORS);
        }
        if (!userPreDestroy.isEmpty()) {
            configuration.addPreDestroyInterceptor(weaved(userPreDestroy), InterceptorOrder.ComponentPreDestroy.INTERCEPTOR_USER_INTERCEPTORS);
        }

        if (description.isPassivationApplicable()) {
            if (!userPrePassivate.isEmpty()) {
                configuration.addPrePassivateInterceptor(weaved(userPrePassivate), InterceptorOrder.ComponentPassivation.INTERCEPTOR_USER_INTERCEPTORS);
            }

            if (!userPostActivate.isEmpty()) {
                configuration.addPostActivateInterceptor(weaved(userPostActivate), InterceptorOrder.ComponentPassivation.INTERCEPTOR_USER_INTERCEPTORS);
            }
        }

        // @AroundInvoke interceptors
        final List<InterceptorDescription> classInterceptors = description.getClassInterceptors();
        final Map<MethodIdentifier, List<InterceptorDescription>> methodInterceptors = description.getMethodInterceptors();

        if (description.isIntercepted()) {

            for (final Method method : configuration.getDefinedComponentMethods()) {

                //now add the interceptor that initializes and the interceptor that actually invokes to the end of the interceptor chain

                final MethodIdentifier identifier = MethodIdentifier.getIdentifier(method.getReturnType(), method.getName(), method.getParameterTypes());

                final List<InterceptorFactory> userAroundInvokes = new ArrayList<InterceptorFactory>();
                final List<InterceptorFactory> userAroundTimeouts = new ArrayList<InterceptorFactory>();
                // first add the default interceptors (if not excluded) to the deque
                final boolean requiresTimerChain = description.isTimerServiceRequired() && timeoutMethods.contains(identifier);
                if (!description.isExcludeDefaultInterceptors() && !description.isExcludeDefaultInterceptors(identifier)) {
                    for (InterceptorDescription interceptorDescription : description.getDefaultInterceptors()) {
                        String interceptorClassName = interceptorDescription.getInterceptorClassName();
                        List<InterceptorFactory> aroundInvokes = userAroundInvokesByInterceptorClass.get(interceptorClassName);
                        if (aroundInvokes != null) {
                            userAroundInvokes.addAll(aroundInvokes);
                        }
                        if (requiresTimerChain) {
                            List<InterceptorFactory> aroundTimeouts = userAroundTimeoutsByInterceptorClass.get(interceptorClassName);
                            if (aroundTimeouts != null) {
                                userAroundTimeouts.addAll(aroundTimeouts);
                            }
                        }
                    }
                }

                // now add class level interceptors (if not excluded) to the deque
                if (!description.isExcludeClassInterceptors(identifier)) {
                    for (InterceptorDescription interceptorDescription : classInterceptors) {
                        String interceptorClassName = interceptorDescription.getInterceptorClassName();
                        List<InterceptorFactory> aroundInvokes = userAroundInvokesByInterceptorClass.get(interceptorClassName);
                        if (aroundInvokes != null) {
                            userAroundInvokes.addAll(aroundInvokes);
                        }
                        if (requiresTimerChain) {
                            List<InterceptorFactory> aroundTimeouts = userAroundTimeoutsByInterceptorClass.get(interceptorClassName);
                            if (aroundTimeouts != null) {
                                userAroundTimeouts.addAll(aroundTimeouts);
                            }
                        }
                    }
                }

                // now add method level interceptors for to the deque so that they are triggered after the class interceptors
                List<InterceptorDescription> methodLevelInterceptors = methodInterceptors.get(identifier);
                if (methodLevelInterceptors != null) {
                    for (InterceptorDescription methodLevelInterceptor : methodLevelInterceptors) {
                        String interceptorClassName = methodLevelInterceptor.getInterceptorClassName();
                        List<InterceptorFactory> aroundInvokes = userAroundInvokesByInterceptorClass.get(interceptorClassName);
                        if (aroundInvokes != null) {
                            userAroundInvokes.addAll(aroundInvokes);
                        }
                        if (requiresTimerChain) {
                            List<InterceptorFactory> aroundTimeouts = userAroundTimeoutsByInterceptorClass.get(interceptorClassName);
                            if (aroundTimeouts != null) {
                                userAroundTimeouts.addAll(aroundTimeouts);
                            }
                        }
                    }
                }
                configuration.addComponentInterceptor(method, new UserInterceptorFactory(weaved(userAroundInvokes), weaved(userAroundTimeouts)), InterceptorOrder.Component.INTERCEPTOR_USER_INTERCEPTORS);
            }
        }
    }


}