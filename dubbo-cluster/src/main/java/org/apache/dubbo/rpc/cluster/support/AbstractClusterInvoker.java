/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.cluster.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.ClusterInvoker;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_LOADBALANCE;
import static org.apache.dubbo.common.constants.CommonConstants.LOADBALANCE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.CLUSTER_AVAILABLE_CHECK_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.CLUSTER_STICKY_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_CLUSTER_AVAILABLE_CHECK;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_CLUSTER_STICKY;

/**
 * AbstractClusterInvoker
 */
public abstract class AbstractClusterInvoker<T> implements ClusterInvoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractClusterInvoker.class);

    protected Directory<T> directory;

    protected boolean availablecheck;

    private AtomicBoolean destroyed = new AtomicBoolean(false);

    private volatile Invoker<T> stickyInvoker = null;

    public AbstractClusterInvoker() {
    }

    public AbstractClusterInvoker(Directory<T> directory) {
        this(directory, directory.getUrl());
    }

    public AbstractClusterInvoker(Directory<T> directory, URL url) {
        if (directory == null) {
            throw new IllegalArgumentException("service directory == null");
        }

        this.directory = directory;
        //sticky: invoker.isAvailable() should always be checked before using when availablecheck is true.
        this.availablecheck = url.getParameter(CLUSTER_AVAILABLE_CHECK_KEY, DEFAULT_CLUSTER_AVAILABLE_CHECK);
    }

    @Override
    public Class<T> getInterface() {
        return getDirectory().getInterface();
    }

    @Override
    public URL getUrl() {
        return getDirectory().getConsumerUrl();
    }

    public URL getRegistryUrl() {
        return getDirectory().getUrl();
    }

    @Override
    public boolean isAvailable() {
        Invoker<T> invoker = stickyInvoker;
        if (invoker != null) {
            return invoker.isAvailable();
        }
        return getDirectory().isAvailable();
    }

    public Directory<T> getDirectory() {
        return directory;
    }

    @Override
    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            getDirectory().destroy();
        }
    }

    @Override
    public boolean isDestroyed() {
        return destroyed.get();
    }

    /**
     * Select a invoker using loadbalance policy.</br>
     * a) Firstly, select an invoker using loadbalance. If this invoker is in previously selected list, or,
     * if this invoker is unavailable, then continue step b (reselect), otherwise return the first selected invoker</br>
     * <p>
     * b) Reselection, the validation rule for reselection: selected > available. This rule guarantees that
     * the selected invoker has the minimum chance to be one in the previously selected list, and also
     * guarantees this invoker is available.
     *
     * @param loadbalance load balance policy
     * @param invocation  invocation
     * @param invokers    invoker candidates
     *                    这是包含所有invoker的集合，这些invoker可能有问题，也可能是没问题的
     * @param selected    exclude selected invokers or not
     *                    这里放的invoker是已经被选择过的，但都是有问题的invoker
     * @return the invoker which will final to do invoke.
     * @throws RpcException exception
     */
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {

        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }
        // 获取RPC调用的方法名
        String methodName = invocation == null ? StringUtils.EMPTY_STRING : invocation.getMethodName();

        // 获取sticky属性，粘连连接属性
        // 所谓粘连连接是指，让所有对同一服务相同方法的访问请求，尽可能由同一个invoker提供服务。
        boolean sticky = invokers.get(0).getUrl()
                .getMethodParameter(methodName, CLUSTER_STICKY_KEY, DEFAULT_CLUSTER_STICKY);

        //ignore overloaded method
        // 若当前粘连连接invoker不空，但其没有包含在所有invoker集合中，
        // 说明这个粘连连接invoker已经挂了，则将缓存粘连连接清空
        if (stickyInvoker != null && !invokers.contains(stickyInvoker)) {
            stickyInvoker = null;
        }

        //ignore concurrency problem
        // 代码走到这里，说明粘连连接invoker没有挂掉，然后再进行判断：
        // 若当前开启了粘连连接功能，粘连连接invoker也不空，且粘连连接invoker也没有包含在有问题invoker集合，
        // 则粘连连接invoker可能是可用的。是否可用，需要再进一步判断
        if (sticky && stickyInvoker != null && (selected == null || !selected.contains(stickyInvoker))) {
            // 若可用性检测功能开启了，且粘连连接invoker是可用的，
            // 则直接返回粘连连接invoker，无需再进行负载均衡选择了
            if (availablecheck && stickyInvoker.isAvailable()) {
                return stickyInvoker;
            }
        }

        // 负载均衡选择
        Invoker<T> invoker = doSelect(loadbalance, invocation, invokers, selected);

        // 将选择出的invoker缓存到粘连连接invoker中
        if (sticky) {
            stickyInvoker = invoker;
        }

        return invoker;
    }

    private Invoker<T> doSelect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {

        if (CollectionUtils.isEmpty(invokers)) {
            return null;
        }

        // 若invoker就一个，则直接返回
        if (invokers.size() == 1) {
            return invokers.get(0);
        }

        // 负载均衡选择
        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);

        //If the `invoker` is in the  `selected` or invoker is unavailable && availablecheck is true, reselect.
        // 若当前选择出的invoker包含在有问题invoker集合中，或当前选择出的invoker直接就是不可用的，
        // 则进行重新负载均衡选择
        if ((selected != null && selected.contains(invoker))
                || (!invoker.isAvailable() && getUrl() != null && availablecheck)) {
            try {
                // 重新负载均衡选择
                Invoker<T> rInvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);

                // 若重新选择的invoker不空，则直接返回，不再判断是否可用了
                if (rInvoker != null) {
                    invoker = rInvoker;
                } else {  // 若重新选择的invoker为空，则采用轮询方式再选择一个
                    //Check the index of current selected invoker, if it's not the last one, choose the one at index+1.
                    int index = invokers.indexOf(invoker);
                    try {
                        //Avoid collision  轮询选择一个invoker，其是否为空，是否可用，不再进行判断
                        invoker = invokers.get((index + 1) % invokers.size());
                    } catch (Exception e) {
                        logger.warn(e.getMessage() + " may because invokers list dynamic change, ignore.", e);
                    }
                }
            } catch (Throwable t) {
                logger.error("cluster reselect fail reason is :" + t.getMessage() + " if can not solve, you can set cluster.availablecheck=false in url", t);
            }
        }

        return invoker;
    }

    /**
     * Reselect, use invokers not in `selected` first, if all invokers are in `selected`,
     * just pick an available one using loadbalance policy.
     *
     * @param loadbalance    load balance policy
     * @param invocation     invocation
     * @param invokers       invoker candidates
     * @param selected       exclude selected invokers or not
     * @param availablecheck check invoker available if true
     * @return the reselect result to do invoke
     * @throws RpcException exception
     */
    private Invoker<T> reselect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected, boolean availablecheck) throws RpcException {

        //Allocating one in advance, this list is certain to be used.
        // 其将来存放的invoker，就是再进行负载均衡选择的总集合，相当于前面的invokers集合的作用
        List<Invoker<T>> reselectInvokers = new ArrayList<>(
                invokers.size() > 1 ? (invokers.size() - 1) : invokers.size());

        // First, try picking a invoker not in `selected`.
        // 遍历所有invokers集合，从中挑选出所有可用的invoker
        for (Invoker<T> invoker : invokers) {
            // 若当前遍历invoker不可用，则直接跳过
            if (availablecheck && !invoker.isAvailable()) {
                continue;
            }

            // 能走到这里，说明当前invoker一定是可用的。
            // 判断当前遍历invoker是否在曾被选择过集合，即有问题invoker集合，
            // 若不在，则将其记录到reselectInvokers
            if (selected == null || !selected.contains(invoker)) {
                reselectInvokers.add(invoker);
            }
        } // end-for


        // 走到这里，reselectInvokers中的invoker一定是可用的，且还未被选择过的

        // 若reselectInvokers不空，则负载均衡从reselectInvokers中选择一个invoker
        if (!reselectInvokers.isEmpty()) {
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }

        // 走到这里，说明reselectInvokers为空

        // Just pick an available invoker using loadbalance policy
        if (selected != null) {
            // 遍历有问题invoker集合，从中查找出可用的invoker
            // 注意，以前有问题，可能现在没有问题了
            for (Invoker<T> invoker : selected) {
                if ((invoker.isAvailable()) // available first
                        && !reselectInvokers.contains(invoker)) {
                    reselectInvokers.add(invoker);
                }
            }
        }

        // 若reselectInvokers不空，则负载均衡从reselectInvokers中选择一个invoker
        if (!reselectInvokers.isEmpty()) {
            return loadbalance.select(reselectInvokers, getUrl(), invocation);
        }

        return null;
    }

    @Override
    public Result invoke(final Invocation invocation) throws RpcException {
        checkWhetherDestroyed();

        // binding attachments into invocation.
//        Map<String, Object> contextAttachments = RpcContext.getClientAttachment().getObjectAttachments();
//        if (contextAttachments != null && contextAttachments.size() != 0) {
//            ((RpcInvocation) invocation).addObjectAttachmentsIfAbsent(contextAttachments);
//        }

        // 通过路由策略，将不符合路由规则的invoker过滤掉
        List<Invoker<T>> invokers = list(invocation);
        // 获取负载均衡策略，并创建相应的负载均衡实例
        LoadBalance loadbalance = initLoadBalance(invokers, invocation);
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);
        // 调用具体的集群容错策略中的doInvoke()
        return doInvoke(invocation, invokers, loadbalance);
    }

    protected void checkWhetherDestroyed() {
        if (destroyed.get()) {
            throw new RpcException("Rpc cluster invoker for " + getInterface() + " on consumer " + NetUtils.getLocalHost()
                    + " use dubbo version " + Version.getVersion()
                    + " is now destroyed! Can not invoke any more.");
        }
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + getUrl().toString();
    }

    protected void checkInvokers(List<Invoker<T>> invokers, Invocation invocation) {
        if (CollectionUtils.isEmpty(invokers)) {
            throw new RpcException(RpcException.NO_INVOKER_AVAILABLE_AFTER_FILTER, "Failed to invoke the method "
                    + invocation.getMethodName() + " in the service " + getInterface().getName()
                    + ". No provider available for the service " + getDirectory().getConsumerUrl().getServiceKey()
                    + " from registry " + getDirectory().getUrl().getAddress()
                    + " on the consumer " + NetUtils.getLocalHost()
                    + " using the dubbo version " + Version.getVersion()
                    + ". Please check if the providers have been started and registered.");
        }
    }

    protected Result invokeWithContext(Invoker<T> invoker, Invocation invocation) {
        setContext(invoker);
        Result result;
        try {
            result = invoker.invoke(invocation);
        } finally {
            clearContext(invoker);
        }
        return result;
    }

    protected abstract Result doInvoke(Invocation invocation, List<Invoker<T>> invokers,
                                       LoadBalance loadbalance) throws RpcException;

    protected List<Invoker<T>> list(Invocation invocation) throws RpcException {
        return getDirectory().list(invocation);
    }

    /**
     * Init LoadBalance.
     * <p>
     * if invokers is not empty, init from the first invoke's url and invocation
     * if invokes is empty, init a default LoadBalance(RandomLoadBalance)
     * </p>
     *
     * @param invokers   invokers
     * @param invocation invocation
     * @return LoadBalance instance. if not need init, return null.
     */
    protected LoadBalance initLoadBalance(List<Invoker<T>> invokers, Invocation invocation) {
        if (CollectionUtils.isNotEmpty(invokers)) {
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(
                    invokers.get(0).getUrl().getMethodParameter(
                            RpcUtils.getMethodName(invocation), LOADBALANCE_KEY, DEFAULT_LOADBALANCE
                    )
            );
        } else {
            return ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(DEFAULT_LOADBALANCE);
        }
    }


    private void setContext(Invoker<T> invoker) {
        RpcContext context = RpcContext.getServiceContext();
        context.setInvoker(invoker)
                .setRemoteAddress(invoker.getUrl().getHost(), invoker.getUrl().getPort())
                .setRemoteApplicationName(invoker.getUrl().getRemoteApplication());
    }

    private void clearContext(Invoker<T> invoker) {
        // do nothing
        RpcContext context = RpcContext.getServiceContext();
        context.setInvoker(null);
    }
}
