package com.abc.spi;

import org.apache.dubbo.common.extension.ExtensionLoader;

public class OrderTest {
    public static void main(String[] args) {
        // 获取到用于加载Order类型扩展类实例的extensionLoader实例
        ExtensionLoader<Order> loader = ExtensionLoader.getExtensionLoader(Order.class);

        Order alipay = loader.getExtension("alipay");
        System.out.println(alipay.way());
    }
}
