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
package org.apache.dubbo.rpc.filter;

import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;

import java.util.Map;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;

/**
 * Perform check whether given provider token is matching with remote token or not. If it does not match
 * it will not allow to invoke remote method.
 *
 * @see Filter
 */
@Activate(group = CommonConstants.PROVIDER, value = TOKEN_KEY)
public class TokenFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv)
            throws RpcException {
        // 获取服务提供者端的token属性
        String token = invoker.getUrl().getParameter(TOKEN_KEY);
        // 如果token不是空，这时候就需要验证token
        if (ConfigUtils.isNotEmpty(token)) {
            // 获取 service type
            Class<?> serviceType = invoker.getInterface();
            //获取inv的附加参数
            Map<String, Object> attachments = inv.getObjectAttachments();
            String remoteToken = (attachments == null ? null : (String) attachments.get(TOKEN_KEY));
            // 如果服务调用者携带的token 与服务提供者端的不一致，就抛出异常
            if (!token.equals(remoteToken)) {
                throw new RpcException("Invalid token! Forbid invoke remote service " + serviceType + " method " + inv.getMethodName() +
                        "() from consumer " + RpcContext.getServiceContext().getRemoteHost() + " to provider " +
                        RpcContext.getServiceContext().getLocalHost()+ ", consumer incorrect token is " + remoteToken);
            }
        }
        return invoker.invoke(inv);
    }

}
