package com.gradle;

import org.gradle.api.Action;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

class ProxyFactory {

    static <T> T createProxy(Object target, Class<T> targetInterface) {
        return newProxyInstance(targetInterface, new ProxyingInvocationHandler(target));
    }

    @SuppressWarnings("unchecked")
    private static <T> T newProxyInstance(Class<T> targetInterface, InvocationHandler invocationHandler) {
        return (T) Proxy.newProxyInstance(targetInterface.getClassLoader(), new Class[]{targetInterface}, invocationHandler);
    }

    private static class ProxyingInvocationHandler implements InvocationHandler {

        private final Object target;

        ProxyingInvocationHandler(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            try {
                Class<?>[] parameterTypes = method.getParameterTypes();
                Method targetMethod = target.getClass().getMethod(method.getName(), convertTypes(parameterTypes, target.getClass().getClassLoader()));
                Object result = targetMethod.invoke(target, toTargetArgs(args));
                if (result == null || isJdkType(result.getClass())) {
                    return result;
                }
                return createProxy(result, method.getReturnType());
            } catch (Exception e) {
                throw new RuntimeException("Failed to invoke " + method + " on " + target + " with args " + Arrays.toString(args), e);
            }
        }

        private Object[] toTargetArgs(Object[] args) {
            if (args == null || args.length == 0) {
                return args;
            }
            if (args.length == 1 && args[0] instanceof Action) {
                return new Object[]{adaptActionArg((Action<?>) args[0])};
            }
            if (Arrays.stream(args).allMatch(it -> isJdkType(it.getClass()))) {
                return args;
            }
            throw new RuntimeException("Unsupported argument types in " + Arrays.toString(args));
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private Action<Object> adaptActionArg(Action action) {
            return arg -> action.execute(createLocalProxy(arg));
        }

        private Object createLocalProxy(Object target) {
            ClassLoader localClassLoader = ProxyFactory.class.getClassLoader();
            return Proxy.newProxyInstance(
                    localClassLoader,
                    convertTypes(collectInterfaces(target.getClass()), localClassLoader),
                    new ProxyingInvocationHandler(target)
            );
        }

        public static Class<?>[] collectInterfaces(final Class<?> type) {
            Set<Class<?>> result = new LinkedHashSet<>();
            collectInterfaces(type, result);
            return result.toArray(new Class<?>[0]);
        }

        private static void collectInterfaces(Class<?> type, Set<Class<?>> result) {
            for (Class<?> candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
                for (Class<?> i : candidate.getInterfaces()) {
                    if (result.add(i)) {
                        collectInterfaces(i, result);
                    }
                }
            }
        }

        private static Class<?>[] convertTypes(Class<?>[] parameterTypes, ClassLoader classLoader) {
            if (parameterTypes.length == 0) {
                return parameterTypes;
            }
            return Arrays.stream(parameterTypes)
                    .map(type -> {
                        try {
                            return classLoader.loadClass(type.getName());
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to load class: " + type.getName(), e);
                        }
                    })
                    .toArray(Class<?>[]::new);
        }

        private boolean isJdkType(Class<?> type) {
            ClassLoader typeClassLoader = type.getClassLoader();
            return typeClassLoader == null || typeClassLoader.equals(Object.class.getClassLoader());
        }
    }

}
