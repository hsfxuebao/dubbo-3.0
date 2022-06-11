package com.abc.test;


import java.util.Set;

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.junit.Test;

import com.abc.adaptive.aclass.AdaptiveOrder;
import com.abc.adaptive.aclass.Order;

public class AdaptiveClassTest {

    @Test
    public void test01() {
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        Order order = loader.getAdaptiveExtension();
        System.out.println(order.way());
    }

    @Test
    public void test02() {
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        Order order = loader.getAdaptiveExtension();
        ((AdaptiveOrder) order).setDefaultName("alipay");
        System.out.println(order.way());
    }

    // adaptive类不是直接扩展类
    @Test
    public void test04() {
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        // 获取到该SPI接口的所有直接扩展类
        Set<String> supportedExtensions = loader.getSupportedExtensions();
        System.out.println(supportedExtensions);
    }
}
