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
package org.apache.dubbo.remoting.zookeeper;

import java.util.List;
import java.util.concurrent.Executor;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.configcenter.ConfigItem;

public interface ZookeeperClient {

    // 创建节点
    void create(String path, boolean ephemeral);
    // 删除节点
    void delete(String path);
    // 获取某个节点的子节点
    List<String> getChildren(String path);
    //为某个节点添加子节点监听器
    List<String> addChildListener(String path, ChildListener listener);

    /**
     * @param path:    directory. All of child of path will be listened.
     * @param listener
     */
    void addDataListener(String path, DataListener listener);

    /**
     * @param path:    directory. All of child of path will be listened.
     * @param listener
     * @param executor another thread
     */
    void addDataListener(String path, DataListener listener, Executor executor);

    void removeDataListener(String path, DataListener listener);
    // 移除某个节点的某个子节点监听器
    void removeChildListener(String path, ChildListener listener);
    // 添加 连接状态监听器
    void addStateListener(StateListener listener);
    // 移除 连接状态监听器
    void removeStateListener(StateListener listener);

    // 是否连接
    boolean isConnected();
    // 关闭
    void close();
    // 获取url
    URL getUrl();

    void create(String path, String content, boolean ephemeral);

    void createOrUpdate(String path, String content, boolean ephemeral, int ticket);

    String getContent(String path);

    ConfigItem getConfigItem(String path);

    boolean checkExists(String path);

}
