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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.support.SerializableClassRegistry;
import org.apache.dubbo.common.serialize.support.SerializationOptimizer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchangers;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LAZY_CONNECT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.STUB_EVENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.remoting.Constants.CHANNEL_READONLYEVENT_SENT_KEY;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;
import static org.apache.dubbo.remoting.Constants.CODEC_KEY;
import static org.apache.dubbo.remoting.Constants.CONNECTIONS_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_HEARTBEAT;
import static org.apache.dubbo.remoting.Constants.DEFAULT_REMOTING_CLIENT;
import static org.apache.dubbo.remoting.Constants.HEARTBEAT_KEY;
import static org.apache.dubbo.remoting.Constants.SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_REMOTING_SERVER;
import static org.apache.dubbo.rpc.Constants.DEFAULT_STUB_EVENT;
import static org.apache.dubbo.rpc.Constants.IS_SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.STUB_EVENT_METHODS_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.CALLBACK_SERVICE_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DEFAULT_SHARE_CONNECTIONS;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.IS_CALLBACK_SERVICE;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_CONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_DISCONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.OPTIMIZER_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.SHARE_CONNECTIONS_KEY;


/**
 * dubbo protocol support.
 */
public class DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    public static final int DEFAULT_PORT = 20880;
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
    private static DubboProtocol INSTANCE;

    /**
     * <host:port,Exchanger>
     * {@link Map<String, List<ReferenceCountExchangeClient>}
     */
    private final Map<String, Object> referenceClientMap = new ConcurrentHashMap<>();
    private static final Object PENDING_OBJECT = new Object();
    private final Set<String> optimizers = new ConcurrentHashSet<>();

    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        @Override
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message) throws RemotingException {

            if (!(message instanceof Invocation)) {
                throw new RemotingException(channel, "Unsupported request: "
                        + (message == null ? null : (message.getClass().getName() + ": " + message))
                        + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
            }

            Invocation inv = (Invocation) message;
            Invoker<?> invoker = getInvoker(channel, inv);  // 获取invoker
            // need to consider backward-compatibility if it's a callback
            if (Boolean.TRUE.toString().equals(inv.getObjectAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                String methodsStr = invoker.getUrl().getParameters().get("methods");
                boolean hasMethod = false;
                if (methodsStr == null || !methodsStr.contains(",")) {
                    hasMethod = inv.getMethodName().equals(methodsStr);
                } else {
                    String[] methods = methodsStr.split(",");
                    for (String method : methods) {
                        if (inv.getMethodName().equals(method)) {
                            hasMethod = true;
                            break;
                        }
                    }
                }
                if (!hasMethod) {
                    logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                            + " not found in callback service interface ,invoke will be ignored."
                            + " please update the api interface. url is:"
                            + invoker.getUrl()) + " ,invocation is :" + inv);
                    return null;
                }
            }
            RpcContext.getServiceContext().setRemoteAddress(channel.getRemoteAddress());
            Result result = invoker.invoke(inv);  // 完成invoker的本地调用计算
            return result.thenApply(Function.identity()); // 将result构建为一个异步结果
        }

        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);

            } else {
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isDebugEnabled()) {
                logger.debug("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, ON_DISCONNECT_KEY);
        }

        private void invoke(Channel channel, String methodKey) {
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        /**
         * FIXME channel.getUrl() always binds to a fixed service, and this service is random.
         * we can choose to use a common service to carry onConnect event if there's no easy way to get the specific
         * service this connection is binding to.
         * @param channel
         * @param url
         * @param methodKey
         * @return
         */
        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }

            RpcInvocation invocation = new RpcInvocation(method, url.getParameter(INTERFACE_KEY), "", new Class<?>[0], new Object[0]);
            invocation.setAttachment(PATH_KEY, url.getPath());
            invocation.setAttachment(GROUP_KEY, url.getGroup());
            invocation.setAttachment(INTERFACE_KEY, url.getParameter(INTERFACE_KEY));
            invocation.setAttachment(VERSION_KEY, url.getVersion());
            if (url.getParameter(STUB_EVENT_KEY, false)) {
                invocation.setAttachment(STUB_EVENT_KEY, Boolean.TRUE.toString());
            }

            return invocation;
        }
    };

    public DubboProtocol() {
        INSTANCE = this;
    }

    public static DubboProtocol getDubboProtocol() {
        if (INSTANCE == null) {
            // load
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME);
        }

        return INSTANCE;
    }

    @Override
    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }

    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke = false;
        boolean isStubServiceInvoke = false;
        int port = channel.getLocalAddress().getPort();
        String path = (String) inv.getObjectAttachments().get(PATH_KEY);

        // if it's callback service on client side
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getObjectAttachments().get(STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            port = channel.getRemoteAddress().getPort();
        }

        //callback
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            path += "." + inv.getObjectAttachments().get(CALLBACK_SERVICE_KEY);
            inv.getObjectAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }

        String serviceKey = serviceKey(
                port,
                path,
                (String) inv.getObjectAttachments().get(VERSION_KEY),
                (String) inv.getObjectAttachments().get(GROUP_KEY)
        );
        // 从缓存map中获取exporter
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        if (exporter == null) {
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " +
                    ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + getInvocationWithoutData(inv));
        }

        return exporter.getInvoker();  // 获取exporter中封装的invoker
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        URL url = invoker.getUrl();

        // export service.
        String key = serviceKey(url);
        // 将invoker封装为了DubboExporter
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);
        // 将exporter写入到缓存map
        exporterMap.put(key, exporter);

        //export an stub service for dispatching event
        Boolean isStubSupportEvent = url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackservice) {
            String stubServiceMethods = url.getParameter(STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }

            }
        }
        // 创建并启动Netty Server
        openServer(url);
        optimizeSerialization(url);

        return exporter;
    }

    private void openServer(URL url) {
        // find server.
        // key格式  ip:暴露协议端口  例如，192.168.59.1:20881
        String key = url.getAddress();

        //client can export a service which's only for server to invoke
        // 表示当前应用是否是provider
        boolean isServer = url.getParameter(IS_SERVER_KEY, true);

        if (isServer) {
            // 从缓存map中获取当前key对应的同异步转换对象。
            // 从key的值可知，一个应用中每个服务暴露协议都会对应一个同异步转换对象，
            // 而一个同异步转换对象会对应一个Netty Server，即一个主机中每个服务暴露
            // 协议会对应创建一个Netty Server（面试题）
            // DCL
            ProtocolServer server = serverMap.get(key);
            if (server == null) {
                synchronized (this) {
                    server = serverMap.get(key);
                    if (server == null) {
                        // 创建同异步转换对象
                        serverMap.put(key, createServer(url));
                    }else {
                        server.reset(url);
                    }
                }
            } else {
                // server supports reset, use together with override
                // 将当前服务的url添加到server中
                server.reset(url);
            }
        }
    }

    private ProtocolServer createServer(URL url) {
        url = URLBuilder.from(url)
                // send readonly event when server closes, it's enabled by default
                .addParameterIfAbsent(CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString())
                // enable heartbeat by default
                .addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT))
                .addParameter(CODEC_KEY, DubboCodec.NAME)
                .build();
        String str = url.getParameter(SERVER_KEY, DEFAULT_REMOTING_SERVER);

        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);
        }

        ExchangeServer server;
        try {
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }

        str = url.getParameter(CLIENT_KEY);
        if (str != null && str.length() > 0) {
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }

        return new DubboProtocolServer(server);
    }

    private void optimizeSerialization(URL url) throws RpcException {
        String className = url.getParameter(OPTIMIZER_KEY, "");
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException("The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }

            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c);
            }

            optimizers.add(className);

        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);

        } catch (InstantiationException | IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);

        }
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        return protocolBindingRefer(type, url);
    }

    @Override
    public <T> Invoker<T> protocolBindingRefer(Class<T> serviceType, URL url) throws RpcException {
        optimizeSerialization(url);

        // create rpc invoker.
        // 创建一个与Dubbo协议相绑定的委托对象invoker，这个invoker可以与provider间建立很多的连接。
        // 而第一个连接就会对应一个Netty Client，通过getClients(url)可以获取到其绑定的这些连接
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
        invokers.add(invoker);

        return invoker;
    }

    private ExchangeClient[] getClients(URL url) {
        // whether to share connection

        // 标识连接是否中可共享的（即一个连接是否可以并行提交多个请求）
        boolean useShareConnect = false;

        // 获取connections属性，该属性指定了当前consumer与provider间
        // 创建的连接数量（非共享的），默认值为0，表示只有一个连接
        int connections = url.getParameter(CONNECTIONS_KEY, 0);

        // ReferenceCountExchangeClient 表示可共享连接client
        List<ReferenceCountExchangeClient> shareClients = null;
        // if not configured, connection is shared, otherwise, one connection for one service
        if (connections == 0) {
            // 当前连接只有一个，所以该连接是可共享的
            useShareConnect = true;

            /*
             * The xml configuration should have a higher priority than properties.
             */
            // 从xml的配置文件的<dubbo:consumer/>中获取shareconnections属性，该属性表示共享连接最多可共享的次数
            String shareConnectionsStr = url.getParameter(SHARE_CONNECTIONS_KEY, (String) null);

            // 若xml中的该属性配置不空，则直接取该值，否则从属性中获取。
            // 注意，此时的connections表示的是共享连接数
            connections = Integer.parseInt(StringUtils.isBlank(shareConnectionsStr) ?
                ConfigUtils.getProperty(SHARE_CONNECTIONS_KEY,  DEFAULT_SHARE_CONNECTIONS) : shareConnectionsStr);
            // 创建相应的共享连接
            shareClients = getSharedClient(url, connections);
        }

        // 代码走到这里，connections的值一定是大于0的，只不过，其可能代表的
        // 是共享连接数量或非共享连接数量
        ExchangeClient[] clients = new ExchangeClient[connections];

        for (int i = 0; i < clients.length; i++) {
            if (useShareConnect) {
                // 直接获取共享连接
                clients[i] = shareClients.get(i);
            } else {
                // 创建非共享连接
                clients[i] = initClient(url);
            }
        }

        return clients;
    }

    /**
     * Get shared connection
     *
     * @param url
     * @param connectNum connectNum must be greater than or equal to 1
     */
    @SuppressWarnings("unchecked")
    private List<ReferenceCountExchangeClient> getSharedClient(URL url, int connectNum) {
        // key为consumer的ip:服务协议port
        String key = url.getAddress();

        // 从缓存中获取clients
        Object clients = referenceClientMap.get(key);

        // ---------------- 1 检测clients可用性 ------------------
        if (clients instanceof List) {
            List<ReferenceCountExchangeClient> typedClients = (List<ReferenceCountExchangeClient>) clients;
            // 判断clients的可用性：只有当clients中的所有client均可用时，
            // 该clients才称为可用的，checkClientCanUse()返回true。只要
            // 有一个client不可用，就返回false
            if (checkClientCanUse(typedClients)) {
                batchClientRefIncr(typedClients);
                return typedClients;
            }
        }


        // ---------------- 2 多线程下的赋值 ------------------
        // 代码走到这里，说明clients中一定存在不可用client

        List<ReferenceCountExchangeClient> typedClients = null;

        synchronized (referenceClientMap) {
            for (; ; ) {
                clients = referenceClientMap.get(key);

                if (clients instanceof List) {
                    // 再判断一次其可用性，若可用，则直接返回该clients
                    typedClients = (List<ReferenceCountExchangeClient>) clients;
                    if (checkClientCanUse(typedClients)) {
                        batchClientRefIncr(typedClients);
                        return typedClients;
                    } else {
                        // 若当前key对应的clients不可用，则将一个Object对象写入该value
                        referenceClientMap.put(key, PENDING_OBJECT);
                        break;
                    }

                    // 若当前clients为Object，则阻塞当前线程
                } else if (clients == PENDING_OBJECT) {
                    try {
                        referenceClientMap.wait();
                    } catch (InterruptedException ignored) {
                    }

                    // 若当前clients为其它情况，则将Object写入到当前key对应的value
                } else {
                    referenceClientMap.put(key, PENDING_OBJECT);
                    break;
                }
            }
        }

        try {
            // ---------------- 3 创建clients ------------------
            // connectNum must be greater than or equal to 1
            connectNum = Math.max(connectNum, 1);

            // If the clients is empty, then the first initialization is
            // 若clients为空，则创建clients
            if (CollectionUtils.isEmpty(typedClients)) {
                typedClients = buildReferenceCountExchangeClientList(url, connectNum);

                // 走到这里，说明当前clients一定是包含不可用client的列表
            } else {
                for (int i = 0; i < typedClients.size(); i++) {
                    ReferenceCountExchangeClient referenceCountExchangeClient = typedClients.get(i);
                    // If there is a client in the list that is no longer available, create a new one to replace him.
                    // 若当前遍历的client不可用，则为其创建一个client
                    if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                        typedClients.set(i, buildReferenceCountExchangeClient(url));
                        continue;
                    }
                    // 若当前遍历的client就是可用的，则为其count增一
                    referenceCountExchangeClient.incrementAndGetCount();
                }
            }

        } finally {
            // ---------------- 4 放入缓存map ------------------
            synchronized (referenceClientMap) {
                if (typedClients == null) {
                    referenceClientMap.remove(key);
                } else {
                    // 将clients写入缓存map
                    referenceClientMap.put(key, typedClients);
                }
                // 唤醒所有阻塞的线程
                referenceClientMap.notifyAll();
            }
        }
        return typedClients;

    }

    /**
     * Check if the client list is all available
     *
     * @param referenceCountExchangeClients
     * @return true-available，false-unavailable
     */
    private boolean checkClientCanUse(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return false;
        }

        // 遍历clients
        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            // As long as one client is not available, you need to replace the unavailable client with the available one.
            // 只要有一个client不可用，就直接结束，返回false
            if (referenceCountExchangeClient == null || referenceCountExchangeClient.getCount() <= 0 || referenceCountExchangeClient.isClosed()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Increase the reference Count if we create new invoker shares same connection, the connection will be closed without any reference.
     *
     * @param referenceCountExchangeClients
     */
    private void batchClientRefIncr(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return;
        }

        // 为每个client的count增一
        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            if (referenceCountExchangeClient != null) {
                referenceCountExchangeClient.incrementAndGetCount();
            }
        }
    }

    /**
     * Bulk build client
     *
     * @param url
     * @param connectNum
     * @return
     */
    private List<ReferenceCountExchangeClient> buildReferenceCountExchangeClientList(URL url, int connectNum) {
        List<ReferenceCountExchangeClient> clients = new ArrayList<>();

        for (int i = 0; i < connectNum; i++) {
            // 创建共享client
            clients.add(buildReferenceCountExchangeClient(url));
        }

        return clients;
    }

    /**
     * Build a single client
     *
     * @param url
     * @return
     */
    private ReferenceCountExchangeClient buildReferenceCountExchangeClient(URL url) {
        // 创建一个client，这个client最终会创建出一个Netty Client
        ExchangeClient exchangeClient = initClient(url);
        // 将该client封装为一个共享连接client
        return new ReferenceCountExchangeClient(exchangeClient);
    }

    /**
     * Create new connection
     *
     * @param url
     */
    private ExchangeClient initClient(URL url) {

        // client type setting.
        String str = url.getParameter(CLIENT_KEY, url.getParameter(SERVER_KEY, DEFAULT_REMOTING_CLIENT));

        url = url.addParameter(CODEC_KEY, DubboCodec.NAME);
        // enable heartbeat by default
        url = url.addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT));

        // BIO is not allowed since it has severe performance issue.
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " + StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        ExchangeClient client;
        try {
            // connection should be lazy
            // 判断当前连接是否是Lazy连接
            if (url.getParameter(LAZY_CONNECT_KEY, false)) {
                client = new LazyConnectExchangeClient(url, requestHandler);

            } else {
                // 正常连接
                client = Exchangers.connect(url, requestHandler);
            }

        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }

        return client;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void destroy() {
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ProtocolServer protocolServer = serverMap.remove(key);

            if (protocolServer == null) {
                continue;
            }

            RemotingServer server = protocolServer.getRemotingServer();

            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Close dubbo server: " + server.getLocalAddress());
                }

                server.close(ConfigurationUtils.getServerShutdownTimeout());

            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

        for (String key : new ArrayList<>(referenceClientMap.keySet())) {
            Object clients = referenceClientMap.remove(key);
            if (clients instanceof List) {
                List<ReferenceCountExchangeClient> typedClients = (List<ReferenceCountExchangeClient>) clients;

                if (CollectionUtils.isEmpty(typedClients)) {
                    continue;
                }

                for (ReferenceCountExchangeClient client : typedClients) {
                    closeReferenceCountExchangeClient(client);
                }
            }
        }

        super.destroy();
    }

    /**
     * close ReferenceCountExchangeClient
     *
     * @param client
     */
    private void closeReferenceCountExchangeClient(ReferenceCountExchangeClient client) {
        if (client == null) {
            return;
        }

        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
            }

            client.close(ConfigurationUtils.getServerShutdownTimeout());

            // TODO
            /*
             * At this time, ReferenceCountExchangeClient#client has been replaced with LazyConnectExchangeClient.
             * Do you need to call client.close again to ensure that LazyConnectExchangeClient is also closed?
             */

        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    /**
     * only log body in debugger mode for size & security consideration.
     *
     * @param invocation
     * @return
     */
    private Invocation getInvocationWithoutData(Invocation invocation) {
        if (logger.isDebugEnabled()) {
            return invocation;
        }
        if (invocation instanceof RpcInvocation) {
            RpcInvocation rpcInvocation = (RpcInvocation) invocation;
            rpcInvocation.setArguments(null);
            return rpcInvocation;
        }
        return invocation;
    }
}
