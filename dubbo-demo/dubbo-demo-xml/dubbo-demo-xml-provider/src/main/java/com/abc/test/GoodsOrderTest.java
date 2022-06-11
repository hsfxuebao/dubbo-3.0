package com.abc.test;


import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.junit.Test;

import com.abc.adaptive.method2.GoodsOrder;

public class GoodsOrderTest {

    @Test
    public void test01() {
        ExtensionLoader<GoodsOrder> loader = ExtensionLoader.getExtensionLoader(GoodsOrder.class);
        // 获取Order的自适应类，不是任何一个Order的实现类
        GoodsOrder goodsOrder = loader.getAdaptiveExtension();

        URL url = URL.valueOf("xxx://localhost:8080/ooo/jjj");
        System.out.println(goodsOrder.pay(url));
        // way()不是自适应方法，其调用会报错
        // System.out.println(order.way());
    }

    @Test
    public void test02() {
        ExtensionLoader<GoodsOrder> loader = ExtensionLoader.getExtensionLoader(GoodsOrder.class);
        // 获取Order的自适应类，不是任何一个Order的实现类
        GoodsOrder goodsOrder = loader.getAdaptiveExtension();

        URL url = URL.valueOf("xxx://localhost:8080/ooo/jjj?goods.order=alipay");
        System.out.println(goodsOrder.pay(url));
        // way()不是自适应方法，其调用会报错
        // System.out.println(order.way());
    }
}
