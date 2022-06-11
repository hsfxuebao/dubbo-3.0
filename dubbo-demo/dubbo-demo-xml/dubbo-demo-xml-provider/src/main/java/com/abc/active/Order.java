package com.abc.active;

import org.apache.dubbo.common.extension.SPI;

@SPI("wechat")
public interface Order {
    String way();
}
