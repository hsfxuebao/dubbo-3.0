package com.abc.adaptive.aclass;

import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.StringUtils;

@Adaptive
public class AdaptiveOrder implements Order {

    private String defaultName;

    // 指定要加载扩展类的名称
    public void setDefaultName(String defaultName) {
        this.defaultName = defaultName;
    }

    @Override
    public String way() {
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        Order order;
        if (StringUtils.isEmpty(defaultName)) {
            // 加载SPI默认名称的扩展类
            order = loader.getDefaultExtension();
        } else {
            // 加载指定名称的扩展类
            order = loader.getExtension(defaultName);
        }
        return order.way();
    }
}
