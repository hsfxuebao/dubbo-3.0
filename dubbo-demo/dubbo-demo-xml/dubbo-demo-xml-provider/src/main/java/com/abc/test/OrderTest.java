package com.abc.test;


import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Protocol;
import org.junit.Test;

import com.abc.dubbospi.Order;

public class OrderTest {

    @Test
    public void test01() {
        // 获取到用于加载Order类型扩展类实例的extensionLoader实例
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);

        Order alipay = loader.getExtension("alipay");
        System.out.println(alipay.way());

        Order wechat = loader.getExtension("wechat");
        System.out.println(wechat.way());
    }

    @Test
    public void test02() {
        // 获取到用于加载Order类型扩展类实例的extensionLoader实例
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);
        Order wechat2 = loader.getExtension("wechat2");
        System.out.println(wechat2.way());

        Order wechat = loader.getExtension("wechat");
        System.out.println(wechat.way());

    }

    @Test
    public void test03() {
        // 获取到用于加载Order类型扩展类实例的extensionLoader实例
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);

        // 以下方式都会报错
        // Order alipay = loader.getExtension(null);
        // Order alipay = loader.getExtension("");

        // 以下方式不会报错，其会加载SPI接口指定的默认名称的扩展类，
        // 但SPI接口默认名称不是这样使用的
        Order defaultPay = loader.getExtension("true");
        System.out.println(defaultPay.way());

    }

    @Test
    public void test4() {
        ExtensionLoader<Protocol> loader = ExtensionLoader
            .getExtensionLoader(Protocol.class);  // 获取SPI接口Protocol的extensionLoader实例

        final Protocol adaptiveExtension = loader.getAdaptiveExtension();
        System.out.println("11111");
    }
}
