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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance.
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "roundrobin";

    // 回收期
    private static final int RECYCLE_PERIOD = 60000;

    // 轮询权重类
    protected static class WeightedRoundRobin {
        // 主机权重
        private int weight;
        // 当前轮询权重值，初始值为0
        private AtomicLong current = new AtomicLong(0);
        // 当前轮询权重值的“增重”时间
        private long lastUpdate;

        public int getWeight() {
            return weight;
        }

        // 修改主机权重时会将轮询权重值清零
        public void setWeight(int weight) {
            this.weight = weight;
            // 将当前轮询权重值进行了清零
            current.set(0);
        }

        // 增重：增加主机权重
        public long increaseCurrent() {
            return current.addAndGet(weight);
        }

        // 减重：减去总主机权重之和
        public void sel(int total) {
            current.addAndGet(-1 * total);
        }

        public long getLastUpdate() {
            return lastUpdate;
        }

        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }

    // 双层map，
    // 外层map的key是全限定方法名，value为内层map，即提供该key方法服务的所有invoker的map
    // 内层map的key为invoker的url，value为轮询权重实例
    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap =
        new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();

    /**
     * get invoker addr list cached for specified invocation
     * <p>
     * <b>for unit test only</b>
     *
     * @param invokers
     * @param invocation
     * @return
     */
    protected <T> Collection<String> getInvokerAddrList(List<Invoker<T>> invokers, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        Map<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map != null) {
            return map.keySet();
        }
        return null;
    }

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 获取外层map的key
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        // 获取指定key的所有invoker构成的map，即内层map。
        // 若不存在，则创建一个内层map，再放入其中
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        int totalWeight = 0;
        // 记录当前最大的轮询权重值
        long maxCurrent = Long.MIN_VALUE;
        long now = System.currentTimeMillis();
        Invoker<T> selectedInvoker = null;
        WeightedRoundRobin selectedWRR = null;

        // 遍历所有invoker，为它们的轮询权重值增重，并挑选出具有最大轮询权重值的invoker
        for (Invoker<T> invoker : invokers) {
            // 获取内层map的key
            String identifyString = invoker.getUrl().toIdentityString();
            // 获取主机权重
            int weight = getWeight(invoker, invocation);
            // 从缓存map中获取指定key的轮询权重，若不存在，则创建一个轮询权重实例，再放入其中
            WeightedRoundRobin weightedRoundRobin = map.computeIfAbsent(identifyString, k -> {
                WeightedRoundRobin wrr = new WeightedRoundRobin();
                wrr.setWeight(weight);
                return wrr;
            });

            // 只有预热权重会发生变化
            if (weight != weightedRoundRobin.getWeight()) {
                //weight changed
                weightedRoundRobin.setWeight(weight);
            }

            // 增重
            long cur = weightedRoundRobin.increaseCurrent();
            // 记录增重时间
            weightedRoundRobin.setLastUpdate(now);

            // 若当前增重后的轮询权重值大于当前记录的最大的轮询权重值，
            // 则修改记录的最大轮询权重值，并将当前invoker记录下来
            if (cur > maxCurrent) {
                maxCurrent = cur;
                selectedInvoker = invoker;
                selectedWRR = weightedRoundRobin;
            }
            // 计算所有invoker的主机权重和
            totalWeight += weight;
        }  // end-for

        // 这里的不等，只有一种可能： invokers.size() < map.size()，不可能出现大于的情况
        // 小于说明出现了invoker宕机，而大于则是扩容。但扩容后在执行前面的for()时，会使map的size()增加，
        // 然后这里的invokers.size()与map.size()就又相等了。
        if (invokers.size() != map.size()) {
            // map.entrySet() 获取一个set集合，其元素为entry
            // removeIf() 的作用是，其参数predicate若为true,则将当前元素删除
            // now - item.getValue().getLastUpdate() 计算出的是，距离上次增重已经过去多久了
            // predicate条件为，若当前invoker距离上次增重时长已经超过了回收期，则说明当前invoker
            // 已经宕机很久了，就可以将其从缓存map中删除了
            map.entrySet().removeIf(item -> now - item.getValue().getLastUpdate() > RECYCLE_PERIOD);
        }

        // 若选择出的invoker不为null，则返回该invoker
        if (selectedInvoker != null) {
            // 减重
            selectedWRR.sel(totalWeight);
            return selectedInvoker;
        }
        // should not happen here
        return invokers.get(0);
    }

}
