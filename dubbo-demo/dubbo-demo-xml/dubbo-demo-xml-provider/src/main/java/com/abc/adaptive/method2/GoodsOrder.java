package com.abc.adaptive.method2;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

@SPI("wechat")
public interface GoodsOrder {
    String way();

    @Adaptive
    String pay(URL url);
}
