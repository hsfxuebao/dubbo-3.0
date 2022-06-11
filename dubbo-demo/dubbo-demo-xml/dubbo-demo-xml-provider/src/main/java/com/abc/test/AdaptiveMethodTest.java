package com.abc.test;


import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.junit.Test;

import com.abc.adaptive.method.Order;

public class AdaptiveMethodTest {

    @Test
    public void test01() {
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        // 获取Order的自适应类，不是任何一个Order的实现类
        Order order = loader.getAdaptiveExtension();

        URL url = URL.valueOf("xxx://localhost:8080/ooo/jjj");
        System.out.println(order.pay(url));
        // way()不是自适应方法，其调用会报错
        // System.out.println(order.way());
    }

    @Test
    public void test02() {
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        // 获取Order的自适应类，不是任何一个Order的实现类
        Order order = loader.getAdaptiveExtension();

        URL url = URL.valueOf("xxx://localhost:8080/ooo/jjj?order=alipay");
        System.out.println(order.pay(url));
        // way()不是自适应方法，其调用会报错
        // System.out.println(order.way());
    }
}
