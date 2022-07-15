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
package org.apache.dubbo.rpc.protocol.dubbo.filter;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;

import com.alibaba.fastjson.JSON;

/**
 * TraceFilter
 */
@Activate(group = CommonConstants.PROVIDER)
public class TraceFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(TraceFilter.class);

    private static final String TRACE_MAX = "trace.max";

    private static final String TRACE_COUNT = "trace.count";

    // 缓存
    private static final ConcurrentMap<String, Set<Channel>> TRACERS = new ConcurrentHashMap<>();

    // 添加Tracer
    public static void addTracer(Class<?> type, String method, Channel channel, int max) {
        channel.setAttribute(TRACE_MAX, max);
        // 统计count
        channel.setAttribute(TRACE_COUNT, new AtomicInteger());
        // 拼装key，如果method没有的话就使用type的全类名， 如果有method话就是 全类名.方法名
        String key = method != null && method.length() > 0 ? type.getName() + "." + method : type.getName();
        // 从缓存中获取，没有就创建一个
        Set<Channel> channels = TRACERS.computeIfAbsent(key, k -> new ConcurrentHashSet<>());
        // 添加到set集合中
        channels.add(channel);
    }

    // 移除tracer
    public static void removeTracer(Class<?> type, String method, Channel channel) {
        // 先从channel 中移除这两个属性
        channel.removeAttribute(TRACE_MAX);
        channel.removeAttribute(TRACE_COUNT);
        // 拼装key
        String key = method != null && method.length() > 0 ? type.getName() + "." + method : type.getName();
        Set<Channel> channels = TRACERS.get(key);
        if (channels != null) {
            // 移除
            channels.remove(channel);
        }
    }

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 开始时间
        long start = System.currentTimeMillis();
        Result result = invoker.invoke(invocation);
        // 结束时间
        long end = System.currentTimeMillis();
        if (TRACERS.size() > 0) {
            // 拼装key，先使用全类名.方法名的 形式
            String key = invoker.getInterface().getName() + "." + invocation.getMethodName(); // 接口全路径.方法名
            // 获取对应tracers
            Set<Channel> channels = TRACERS.get(key);
            // 没有对应的tracer 就使用 接口全类名做 key
            if (channels == null || channels.isEmpty()) {
                // 接口名
                key = invoker.getInterface().getName();
                channels = TRACERS.get(key);
            }
            if (CollectionUtils.isNotEmpty(channels)) {
                //遍历这堆channel
                for (Channel channel : new ArrayList<>(channels)) {
                    //不是关闭状态的话
                    if (channel.isConnected()) {
                        try {
                            int max = 1;
                            // 从channel中获取trace.max属性
                            Integer m = (Integer) channel.getAttribute(TRACE_MAX);
                            //如果 m  不是null的话，max=m
                            if (m != null) {
                                max = m;
                            }
                            int count = 0;

                            AtomicInteger c = (AtomicInteger) channel.getAttribute(TRACE_COUNT);
                            if (c == null) {
                                c = new AtomicInteger();
                                channel.setAttribute(TRACE_COUNT, c);
                            }
                            // 调用次数+1
                            count = c.getAndIncrement();
                            // 当count小于max的时候
                            if (count < max) {
                                // 获取那个终端上的头 ，这个不用纠结
                                String prompt = channel.getUrl().getParameter(Constants.PROMPT_KEY, Constants.DEFAULT_PROMPT);
                                // 发送 耗时信息
                                channel.send("\r\n" + RpcContext.getServiceContext().getRemoteAddress() + " -> "
                                        + invoker.getInterface().getName()
                                        + "." + invocation.getMethodName()
                                        + "(" + JSON.toJSONString(invocation.getArguments()) + ")" + " -> " + JSON.toJSONString(result.getValue())
                                        + "\r\nelapsed: " + (end - start) + " ms."
                                        + "\r\n\r\n" + prompt);
                            }
                            // 当调用总次数超过 max的时候
                            if (count >= max - 1) {
                                // 就将channel移除
                                channels.remove(channel);
                            }
                        } catch (Throwable e) {
                            channels.remove(channel);
                            logger.warn(e.getMessage(), e);
                        }
                    } else {
                        channels.remove(channel);
                    }
                }
            }
        }
        // 返回result
        return result;
    }

}
