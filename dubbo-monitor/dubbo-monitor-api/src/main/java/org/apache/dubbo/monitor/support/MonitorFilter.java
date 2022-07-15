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
package org.apache.dubbo.monitor.support;

import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.monitor.Constants.COUNT_PROTOCOL;
import static org.apache.dubbo.rpc.Constants.INPUT_KEY;
import static org.apache.dubbo.rpc.Constants.OUTPUT_KEY;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.monitor.Monitor;
import org.apache.dubbo.monitor.MonitorFactory;
import org.apache.dubbo.monitor.MonitorService;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.support.RpcUtils;
/**
 * MonitorFilter. (SPI, Singleton, ThreadSafe)
 */
@Activate(group = {PROVIDER})
public class MonitorFilter implements Filter, Filter.Listener {

    private static final Logger logger = LoggerFactory.getLogger(MonitorFilter.class);
    private static final String MONITOR_FILTER_START_TIME = "monitor_filter_start_time";
    private static final String MONITOR_REMOTE_HOST_STORE = "monitor_remote_host_store";

    /**
     * The Concurrent counter
     * 缓存着 key=接口名.方法名       value= 计数器
     */
    private final ConcurrentMap<String, AtomicInteger> concurrents = new ConcurrentHashMap<String, AtomicInteger>();

    /**
     * The MonitorFactory
     */
    private MonitorFactory monitorFactory;

    public void setMonitorFactory(MonitorFactory monitorFactory) {
        this.monitorFactory = monitorFactory;
    }


    /**
     * The invocation interceptor,it will collect the invoke data about this invocation and send it to monitor center
     *
     * @param invoker    service
     * @param invocation invocation.
     * @return {@link Result} the invoke result
     * @throws RpcException
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 判断是否有monitor参数
        if (invoker.getUrl().hasAttribute(MONITOR_KEY)) {
            invocation.put(MONITOR_FILTER_START_TIME, System.currentTimeMillis());
            invocation.put(MONITOR_REMOTE_HOST_STORE, RpcContext.getServiceContext().getRemoteHost());
            // 计数器+1
            getConcurrent(invoker, invocation).incrementAndGet(); // count up
        }
        // 进行调用
        return invoker.invoke(invocation); // proceed invocation chain
    }

    // concurrent counter
    private AtomicInteger getConcurrent(Invoker<?> invoker, Invocation invocation) {
        String key = invoker.getInterface().getName() + "." + invocation.getMethodName();
        return concurrents.computeIfAbsent(key, k -> new AtomicInteger());
    }

    @Override
    public void onResponse(Result result, Invoker<?> invoker, Invocation invocation) {
        if (invoker.getUrl().hasAttribute(MONITOR_KEY)) {
            // 进行收集
            collect(invoker, invocation, result, (String) invocation.get(MONITOR_REMOTE_HOST_STORE), (long) invocation.get(MONITOR_FILTER_START_TIME), false);
            getConcurrent(invoker, invocation).decrementAndGet(); // count down
        }
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {
        if (invoker.getUrl().hasAttribute(MONITOR_KEY)) {
            // 进行收集
            collect(invoker, invocation, null, (String) invocation.get(MONITOR_REMOTE_HOST_STORE), (long) invocation.get(MONITOR_FILTER_START_TIME), true);
            getConcurrent(invoker, invocation).decrementAndGet(); // count down
        }
    }

    /**
     * The collector logic, it will be handled by the default monitor
     *
     * @param invoker
     * @param invocation
     * @param result     the invoke result
     * @param remoteHost the remote host address
     * @param start      the timestamp the invoke begin
     * @param error      if there is an error on the invoke
     */
    private void collect(Invoker<?> invoker, Invocation invocation, Result result, String remoteHost, long start, boolean error) {
        try {
            Object monitorUrl;
            monitorUrl = invoker.getUrl().getAttribute(MONITOR_KEY);
            if(monitorUrl instanceof URL) {
                // 根据monitorUrl 从监控工厂中获取对应的监控对象
                Monitor monitor = monitorFactory.getMonitor((URL) monitorUrl);
                if (monitor == null) {
                    return;
                }
                URL statisticsURL = createStatisticsUrl(invoker, invocation, result, remoteHost, start, error);
                // monitor收集
                monitor.collect(statisticsURL.toSerializableURL());
            }
        } catch (Throwable t) {
            logger.warn("Failed to monitor count service " + invoker.getUrl() + ", cause: " + t.getMessage(), t);
        }
    }

    /**
     * Create statistics url
     *
     * @param invoker
     * @param invocation
     * @param result
     * @param remoteHost
     * @param start
     * @param error
     * @return
     */
    private URL createStatisticsUrl(Invoker<?> invoker, Invocation invocation, Result result, String remoteHost, long start, boolean error) {
        // ---- service statistics ----
        // 调用耗时
        long elapsed = System.currentTimeMillis() - start; // invocation cost
        // 获取当前的并发数
        int concurrent = getConcurrent(invoker, invocation).get(); // current concurrent count
        String application = invoker.getUrl().getApplication();
        // 服务名
        String service = invoker.getInterface().getName(); // service name
        // 方法名
        String method = RpcUtils.getMethodName(invocation); // method name
        // 分组
        String group = invoker.getUrl().getGroup();
        String version = invoker.getUrl().getVersion();

        int localPort;
        String remoteKey, remoteValue;
        // 服务调用者端
        if (CONSUMER_SIDE.equals(invoker.getUrl().getSide())) {
            // ---- for service consumer ----
            localPort = 0;
            remoteKey = MonitorService.PROVIDER;
            // 远端地址，在这里就是服务提供者方的地址
            remoteValue = invoker.getUrl().getAddress();
        } else {
            // 服务提供者端
            // ---- for service provider ----
            // 获取服务提供者端的port
            localPort = invoker.getUrl().getPort();
            remoteKey = MonitorService.CONSUMER;
            // 远端地址，这里是服务提供者方的地址
            remoteValue = remoteHost;
        }
        String input = "", output = "";
        // 这两个会在Serialize层添加上
        // 获取input
        if (invocation.getAttachment(INPUT_KEY) != null) {
            input = invocation.getAttachment(INPUT_KEY);
        }
        // 获取output
        if (result != null && result.getAttachment(OUTPUT_KEY) != null) {
            output = result.getAttachment(OUTPUT_KEY);
        }

        return new ServiceConfigURL(COUNT_PROTOCOL, NetUtils.getLocalHost(), localPort, service + PATH_SEPARATOR + method, MonitorService.APPLICATION, application, MonitorService.INTERFACE, service, MonitorService.METHOD, method, remoteKey, remoteValue, error ? MonitorService.FAILURE : MonitorService.SUCCESS, "1", MonitorService.ELAPSED, String.valueOf(elapsed), MonitorService.CONCURRENT, String.valueOf(concurrent), INPUT_KEY, input, OUTPUT_KEY, output, GROUP_KEY, group, VERSION_KEY, version);
    }


}
