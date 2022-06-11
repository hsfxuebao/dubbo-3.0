package com.abc.test;


import java.util.Set;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.junit.Test;

import com.abc.wrapper.Order;

public class WrapperTest {

    @Test
    public void test01() {
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        Order order = loader.getAdaptiveExtension();
        URL url = URL.valueOf("xxx://localhost:8080/ooo/jjj");
        System.out.println(order.pay(url));
    }

    @Test
    public void test02() {
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        Order order = loader.getAdaptiveExtension();
        URL url = URL.valueOf("xxx://localhost:8080/ooo/jjj?order=alipay");
        System.out.println(order.pay(url));
    }

    // wrapper类不是直接扩展类
    @Test
    public void test03() {
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        // 获取到该SPI接口的所有直接扩展类
        Set<String> supportedExtensions = loader.getSupportedExtensions();
        System.out.println(supportedExtensions);
    }
}
