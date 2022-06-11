package com.abc.test;


import java.util.List;
import java.util.Set;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.junit.Test;

import com.abc.active.Order;

public class ActiveTest {

    @Test
    public void test01() {
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        URL url = URL.valueOf("xxx://localhost:8080/ooo/jjj");

        // 一次性激活（加载）一批扩展类实例，这里指定的是激活所有group为“online"的扩展类
        List<Order> online = loader.getActivateExtension(url, "", "online");

        for (Order order : online) {
            System.out.println(order.way());
        }
    }

    @Test
    public void test02() {
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        URL url = URL.valueOf("xxx://localhost:8080/ooo/jjj");

        // 一次性激活（加载）一批扩展类实例，这里指定的是激活所有group为“offline"的扩展类
        List<Order> online = loader.getActivateExtension(url, "", "offline");

        for (Order order : online) {
            System.out.println(order.way());
        }
    }

    @Test
    public void test03() {
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        URL url = URL.valueOf("xxx://localhost:8080/ooo/jjj?ooo=alipay");

        // getActivateExtension()的参数二与三的关系是“或”
        // 参数二指定的是要激活的value
        // 参数三指定的是要激活的group
        List<Order> online = loader.getActivateExtension(url, "ooo", "online");

        for (Order order : online) {
            System.out.println(order.way());
        }
    }

    @Test
    public void test04() {
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        URL url = URL.valueOf("xxx://localhost:8080/ooo/jjj?ooo=alipay");

        List<Order> online = loader.getActivateExtension(url, "ooo", "offline");

        for (Order order : online) {
            System.out.println(order.way());
        }
    }

    // activate类是直接扩展类
    @Test
    public void test05() {
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        // 获取到该SPI接口的所有直接扩展类
        Set<String> supportedExtensions = loader.getSupportedExtensions();
        System.out.println(supportedExtensions);
    }
}
