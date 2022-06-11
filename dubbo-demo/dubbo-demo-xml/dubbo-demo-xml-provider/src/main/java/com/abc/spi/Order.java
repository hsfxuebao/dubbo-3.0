package com.abc.spi;

import org.apache.dubbo.common.extension.SPI;

/**
 * 下单接口
 */
@SPI("wechat")
public interface Order {
    // 支付方式
    String way();
}
