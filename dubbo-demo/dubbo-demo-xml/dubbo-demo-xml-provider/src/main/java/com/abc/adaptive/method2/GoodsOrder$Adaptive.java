package com.abc.adaptive.method2;

import org.apache.dubbo.common.extension.ExtensionLoader;

public class GoodsOrder$Adaptive implements com.abc.adaptive.method2.GoodsOrder {

    public java.lang.String pay(org.apache.dubbo.common.URL arg0) {
        if (arg0 == null) {
            throw new IllegalArgumentException("url == null");
        }
        org.apache.dubbo.common.URL url = arg0;
        String extName = url.getParameter("goods.order", "wechat");
        if (extName == null) {
            throw new IllegalStateException(
                "Failed to get extension (com.abc.adaptive.method2.GoodsOrder) name from url (" + url.toString()
                    + ") use keys([goods.order])");
        }
        com.abc.adaptive.method2.GoodsOrder extension = (com.abc.adaptive.method2.GoodsOrder) ExtensionLoader
            .getExtensionLoader(com.abc.adaptive.method2.GoodsOrder.class).getExtension(extName);
        return extension.pay(arg0);
    }

    public java.lang.String way() {
        throw new UnsupportedOperationException(
            "The method public abstract java.lang.String com.abc.adaptive.method2.GoodsOrder.way() of interface com.abc.adaptive.method2.GoodsOrder is not adaptive method!");
    }
}
