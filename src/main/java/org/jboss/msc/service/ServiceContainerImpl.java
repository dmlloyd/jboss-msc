/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.msc.service;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.jboss.msc.ref.Reaper;
import org.jboss.msc.ref.Reference;
import org.jboss.msc.ref.WeakReference;
import org.jboss.msc.value.ImmediateValue;
import org.jboss.msc.value.Value;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class ServiceContainerImpl implements ServiceContainer {
    final Object lock = new Object();
    final ServiceControllerImpl<ServiceContainer> root;

    private static final class ExecutorHolder {
        private static final Executor VALUE;

        static {
            final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
                public Thread newThread(final Runnable r) {
                    final Thread thread = new Thread(r);
                    thread.setDaemon(true);
                    thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                        public void uncaughtException(final Thread t, final Throwable e) {
                            e.printStackTrace(System.err);
                        }
                    });
                    return thread;
                }
            });
            executor.allowCoreThreadTimeOut(true);
            executor.setCorePoolSize(1);
            VALUE = executor;
        }

        private ExecutorHolder() {
        }
    }

    private static final class ShutdownHookHolder {
        private static final Set<Reference<ServiceContainerImpl, Void>> containers;
        private static boolean down = false;

        static {
            containers = new HashSet<Reference<ServiceContainerImpl, Void>>();
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                public Void run() {
                    final Thread hook = new Thread(new Runnable() {
                        public void run() {
                            // shut down all services in all containers.
                            final Set<Reference<ServiceContainerImpl, Void>> set = containers;
                            final LatchListener listener;
                            synchronized (set) {
                                down = true;
                                listener = new LatchListener(set.size());
                                for (Reference<ServiceContainerImpl, Void> containerRef : set) {
                                    final ServiceContainerImpl container = containerRef.get();
                                    if (container == null) {
                                        continue;
                                    }
                                    final ServiceControllerImpl<ServiceContainer> root = container.root;
                                    root.setMode(ServiceController.Mode.NEVER);
                                    root.addListener(listener);
                                }
                                set.clear();
                            }
                            // wait for all services to finish.
                            for (;;) try {
                                if (! listener.await(10L, TimeUnit.SECONDS)) {
                                    System.err.println("Failed to shut down in 10 seconds; exiting");
                                    return;
                                }
                                break;
                            } catch (InterruptedException e) {
                            }
                        }
                    });
                    hook.setDaemon(true);
                    Runtime.getRuntime().addShutdownHook(hook);
                    return null;
                }
            });
        }

        private ShutdownHookHolder() {
        }
    }

    private volatile Executor executor;

    ServiceContainerImpl() {
        final Set<Reference<ServiceContainerImpl, Void>> set = ShutdownHookHolder.containers;
        synchronized (set) {
            // if the shutdown hook was triggered, then no services can ever come up in any new containers.
            final boolean down = ShutdownHookHolder.down;
            final ServiceBuilderImpl<ServiceContainer> builder = new ServiceBuilderImpl<ServiceContainer>(this, new ImmediateValue<Service<ServiceContainer>>(new Service<ServiceContainer>() {
                public void start(final StartContext context) throws StartException {
                }

                public void stop(final StopContext context) {
                }

                public ServiceContainer getValue() throws IllegalStateException {
                    return ServiceContainerImpl.this;
                }
            }), null);
            root = builder.setInitialMode(down ? ServiceController.Mode.NEVER : ServiceController.Mode.AUTOMATIC).create();
            if (! down) {
                set.add(new WeakReference<ServiceContainerImpl, Void>(this, null, new Reaper<ServiceContainerImpl, Void>() {
                    public void reap(final org.jboss.msc.ref.Reference<ServiceContainerImpl, Void> reference) {
                        ShutdownHookHolder.containers.remove(reference);
                    }
                }));
            }
        }
    }

    public <T> ServiceBuilderImpl<T> buildService(final Value<? extends Service<? extends T>> service) {
        return buildService(null, service);
    }

    public <T> ServiceBuilderImpl<T> buildService(final ServiceName serviceName, final Value<? extends Service<? extends T>> service) {
        final ServiceBuilderImpl<T> builder = new ServiceBuilderImpl<T>(this, service, serviceName);
        builder.addDependency(root);
        return builder;
    }

    public void setExecutor(final Executor executor) {
        this.executor = executor;
    }

    public void shutdown() {
        root.setMode(ServiceController.Mode.NEVER);
    }

    protected void finalize() throws Throwable {
        root.setMode(ServiceController.Mode.NEVER);
    }

    static final class LatchListener extends CountDownLatch implements ServiceListener<Object> {

        public LatchListener(int count) {
            super(count);
        }

        public void listenerAdded(final ServiceController<? extends Object> serviceController) {
            final ServiceController.State state = serviceController.getState();
            if (state == ServiceController.State.DOWN || state == ServiceController.State.REMOVED) {
                countDown();
                serviceController.removeListener(this);
            }
        }

        public void serviceStarting(final ServiceController<? extends Object> serviceController) {
        }

        public void serviceStarted(final ServiceController<? extends Object> serviceController) {
        }

        public void serviceFailed(final ServiceController<? extends Object> serviceController, final StartException reason) {
        }

        public void serviceStopping(final ServiceController<? extends Object> serviceController) {
        }

        public void serviceStopped(final ServiceController<? extends Object> serviceController) {
            countDown();
            serviceController.removeListener(this);
        }

        public void serviceRemoved(final ServiceController<? extends Object> serviceController) {
        }
    }

    Executor getExecutor() {
        final Executor executor = this.executor;
        return executor != null ? executor : ExecutorHolder.VALUE;
    }

    private final ConcurrentMap<ServiceName, ServiceController<?>> registry = new ConcurrentHashMap<ServiceName, ServiceController<?>>();

    public BatchBuilderImpl batchBuilder() {
        return new BatchBuilderImpl(this);
    }

    /**
     * Install a collection of service definitions into the registry.  Will install the services
     * in dependency order.
     *
     * @param serviceBatch The service batch to install
     * @throws ServiceRegistryException If any problems occur resolving the dependencies or adding to the registry.
     */
    void install(final BatchBuilderImpl serviceBatch) throws ServiceRegistryException {
        try {
            resolve(serviceBatch.getBatchServices());
        } catch (ResolutionException e) {
            throw new ServiceRegistryException("Failed to resolve dependencies", e);
        }
    }

    private void resolve(final Map<ServiceName, BatchServiceBuilderImpl<?>> services) throws ServiceRegistryException {
        for (BatchServiceBuilderImpl<?> batchEntry : services.values()) {
            if(!batchEntry.processed)
                doResolve(batchEntry, services);
        }
    }

    @SuppressWarnings({ "unchecked" })
    private <T> void doResolve(BatchServiceBuilderImpl<T> entry, final Map<ServiceName, BatchServiceBuilderImpl<?>> services) throws ServiceRegistryException {
        outer:
        while (entry != null) {
            final Value<? extends Service<T>> serviceValue = entry.getServiceValue();

            final ServiceName name = entry.getName();
            ServiceBuilder<T> builder;
            if ((builder = entry.builder) == null) {
                builder = entry.builder = buildService(name, serviceValue);
            }

            final ServiceName[] deps = entry.getDependencies();
            final ServiceName[] aliases = entry.getAliases();

            while (entry.i < deps.length) {
                final ServiceName dependencyName = deps[entry.i];

                ServiceController<?> dependencyController = registry.get(dependencyName);
                if (dependencyController == null) {
                    final BatchServiceBuilderImpl dependencyEntry = services.get(dependencyName);
                    if (dependencyEntry == null)
                        throw new MissingDependencyException("Missing dependency: " + name + " depends on " + dependencyName + " which can not be found");

                    // Backup the last position, so that we can unroll
                    assert dependencyEntry.prev == null;
                    dependencyEntry.prev = entry;

                    entry.visited = true;
                    entry = dependencyEntry;

                    if (entry.visited)
                        throw new CircularDependencyException("Circular dependency discovered: " + name);

                    continue outer;
                }

                // Either the dep already exists, or we are unrolling and just created it
                builder.addDependency(dependencyController);
                entry.i++;
            }

            // We are resolved.  Lets install
            builder.addListener(new ServiceUnregisterListener(name, aliases));

            for(ServiceListener<? super T> listener : entry.getListeners()) {
                builder.addListener(listener);
            }

            for(BatchInjectionBuilderImpl injection : entry.getInjections()) {
                builder.addValueInjection(
                        valueInjection(serviceValue, builder, injection)
                );
            }
            final ServiceController.Mode initialMode = entry.getInitialMode();
            builder.setInitialMode(ServiceController.Mode.NEVER);
            final ServiceController<?> serviceController = builder.create();
            if (registry.putIfAbsent(name, serviceController) != null) {
                if (! entry.isIfNotExist()) {
                    throw new DuplicateServiceException("Duplicate service name provided: " + name);
                }
            } else {
                for(ServiceName alias : aliases) {
                    if (registry.putIfAbsent(alias, serviceController) != null) {
                        throw new DuplicateServiceException("Duplicate service name provided: " + alias);
                    }
                }
            }
            serviceController.setMode(initialMode == null ? ServiceController.Mode.AUTOMATIC : initialMode);

            // Cleanup
            entry.builder = null;
            BatchServiceBuilderImpl prev = entry.prev;
            entry.prev = null;

            // Unroll!
            entry.processed = true;
            entry.visited = false;
            entry = prev;
        }
    }

    @SuppressWarnings({ "unchecked" })
    private <T> ValueInjection<T> valueInjection(final Value<? extends Service<T>> serviceValue, final ServiceBuilder<T> builder, final BatchInjectionBuilderImpl injection) {
        return new ValueInjection(
                injection.getSource().getValue((Value)serviceValue, builder, this),
                injection.getDestination().getInjector((Value)serviceValue, builder, this)
        );
    }

    public ServiceController<?> getRequiredService(final ServiceName serviceName) throws ServiceNotFoundException {
        final ServiceController<?> controller = getService(serviceName);
        if (controller == null) {
            throw new ServiceNotFoundException("Service " + serviceName + " not found");
        }
        return controller;
    }

    public ServiceController<?> getService(final ServiceName serviceName) {
        return registry.get(serviceName);
    }

    private class ServiceUnregisterListener extends AbstractServiceListener<Object> {
        private final ServiceName serviceName;
        private final ServiceName[] aliases;

        private ServiceUnregisterListener(ServiceName serviceName, ServiceName[] aliases) {
            this.serviceName = serviceName;
            this.aliases = aliases;
        }

        @Override
        public void serviceRemoved(ServiceController serviceController) {
            if(!registry.remove(serviceName, serviceController))
                throw new RuntimeException("Removed service [" + serviceName + "] was not unregistered");
            
            for(ServiceName alias : aliases) {
                if(!registry.remove(alias, serviceController))
                    throw new RuntimeException("Removed service alias [" + alias + "] was not unregistered");
            }
        }
    }
}
