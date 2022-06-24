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
package org.apache.dubbo.rpc.cluster.filter;

import java.util.List;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.cluster.ClusterInvoker;

@Activate(order = 0)
public class DefaultFilterChainBuilder implements FilterChainBuilder {

    /**
     * build consumer/provider filter chain
     *
     * 在构建调用链时方法先获取Filter列表，然后创建与Fitler数量一样多Invoker
     * 结点，接着将这些结点串联在一起，构成一个链表，最后将这个链的首结点返回，随后的调用中，将从首结点开始，依次调用各个结点，
     * 完成调用后沿调用链返回。这里各个Invoker结点的串联是通过与其关联的invoke 方法来完成的。
     */
    @Override
    public <T> Invoker<T> buildInvokerChain(final Invoker<T> originalInvoker, String key, String group) {
        Invoker<T> last = originalInvoker;
        List<Filter> filters = ExtensionLoader.getExtensionLoader(Filter.class).getActivateExtension(originalInvoker.getUrl(), key, group);

        if (!filters.isEmpty()) {
            for (int i = filters.size() - 1; i >= 0; i--) {
                final Filter filter = filters.get(i);
                // 调用buildInvokerChain时会传入invoker参数。
                final Invoker<T> next = last;
                // 通过循环遍历获取到的Filter，同时创建Invoker结点，每个结点对应一个Filter。此时循环内部定义了next指针。
                last = new FilterChainNode<>(originalInvoker, next, filter);
            }
        }

        return last;
    }

    /**
     * build consumer cluster filter chain
     */
    @Override
    public <T> ClusterInvoker<T> buildClusterInvokerChain(final ClusterInvoker<T> originalInvoker, String key, String group) {
        ClusterInvoker<T> last = originalInvoker;
        List<ClusterFilter> filters = ExtensionLoader.getExtensionLoader(ClusterFilter.class).getActivateExtension(originalInvoker.getUrl(), key, group);

        if (!filters.isEmpty()) {
            for (int i = filters.size() - 1; i >= 0; i--) {
                final ClusterFilter filter = filters.get(i);
                final Invoker<T> next = last;
                last = new ClusterFilterChainNode<>(originalInvoker, next, filter);
            }
        }

        return last;
    }

}
