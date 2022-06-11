package com.abc.adaptive.method;

import org.apache.dubbo.common.extension.ExtensionLoader;

public class Order$Adaptive implements com.abc.adaptive.method.Order {

    public java.lang.String pay(org.apache.dubbo.common.URL arg0) {
        if (arg0 == null) {
            throw new IllegalArgumentException("url == null");
        }
        org.apache.dubbo.common.URL url = arg0;
        String extName = url.getParameter("order", "wechat");
        if (extName == null) {
            throw new IllegalStateException(
                "Failed to get extension (com.abc.adaptive.method.Order) name from url (" + url.toString()
                    + ") use keys([order])");
        }
        com.abc.adaptive.method.Order extension =
            (com.abc.adaptive.method.Order) ExtensionLoader.getExtensionLoader(com.abc.adaptive.method.Order.class)
                .getExtension(extName);
        return extension.pay(arg0);
    }

    public java.lang.String way() {
        throw new UnsupportedOperationException(
            "The method public abstract java.lang.String com.abc.adaptive.method.Order.way() of interface com.abc.adaptive.method.Order is not adaptive method!");
    }
}
