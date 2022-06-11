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

import org.apache.dubbo.common.threadlocal.NamedInternalThreadFactory;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.FORKS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.DEFAULT_FORKS;

/**
 * NOTICE! This implementation does not work well with async call.
 *
 * Invoke a specific number of invokers concurrently, usually used for demanding real-time operations, but need to waste more service resources.
 *
 * <a href="http://en.wikipedia.org/wiki/Fork_(topology)">Fork</a>
 */
public class ForkingClusterInvoker<T> extends AbstractClusterInvoker<T> {

    /**
     * Use {@link NamedInternalThreadFactory} to produce {@link org.apache.dubbo.common.threadlocal.InternalThread}
     * which with the use of {@link org.apache.dubbo.common.threadlocal.InternalThreadLocal} in {@link RpcContext}.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(
            new NamedInternalThreadFactory("forking-cluster-timer", true));

    public ForkingClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        try {
            checkInvokers(invokers, invocation);
            // 存放的是挑选出的用于进行并行运行的invoker
            final List<Invoker<T>> selected;
            // 获取forks属性值
            final int forks = getUrl().getParameter(FORKS_KEY, DEFAULT_FORKS);
            // 获取timeout属性值，远程调用超时时限
            final int timeout = getUrl().getParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);
            if (forks <= 0 || forks >= invokers.size()) {
                selected = invokers;
            } else {  // 处理forks取值在(0, invokers.size())范围的情况
                selected = new ArrayList<>(forks);
                while (selected.size() < forks) {
                    // 负载均衡选择一个invoker
                    Invoker<T> invoker = select(loadbalance, invocation, invokers, selected);
                    if (!selected.contains(invoker)) {
                        //Avoid add the same invoker several times.
                        selected.add(invoker);
                    }
                }
            }
            RpcContext.getServiceContext().setInvokers((List) selected);

            // 计数器，记录并行运行异常的invoker数量
            final AtomicInteger count = new AtomicInteger();

            // 队列：存放并行运行结果
            final BlockingQueue<Object> ref = new LinkedBlockingQueue<>();

            // 并行运行
            for (final Invoker<T> invoker : selected) {
                // 使用线程池中的线程执行，这是并行执行的过程
                executor.execute(() -> {
                    try {
                        // 远程调用
                        Result result = invokeWithContext(invoker, invocation);
                        // 将当前invoker执行结果写入到队列
                        ref.offer(result);
                    } catch (Throwable e) {
                        // 若invoker执行过程中出现异常，则计数器加一
                        int value = count.incrementAndGet();
                        if (value >= selected.size()) {
                            // 代码走到这里说明，没有任何一个并行远程调用是成功的。
                            // 为了能够唤醒后面的poll()，这里就将异常信息写入到ref队列
                            ref.offer(e);
                        }
                    }
                });
            }  // end-for
            try {
                // poll()是一个阻塞方法，等待ref中具有一个元素。
                // 只要ref中被写入了一个元素，阻塞马上被唤醒。或一直等待到timeout超时
                // 注意，该poll()方法的执行与前面的并行远程调用的执行也是并行的
                Object ret = ref.poll(timeout, TimeUnit.MILLISECONDS);
                if (ret instanceof Throwable) {
                    Throwable e = (Throwable) ret;
                    throw new RpcException(e instanceof RpcException ? ((RpcException) e).getCode() : 0, "Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e.getCause() != null ? e.getCause() : e);
                }
                return (Result) ret;
            } catch (InterruptedException e) {
                throw new RpcException("Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e);
            }
        } finally {
            // clear attachments which is binding to current thread.
            RpcContext.getClientAttachment().clearAttachments();
        }
    }
}
