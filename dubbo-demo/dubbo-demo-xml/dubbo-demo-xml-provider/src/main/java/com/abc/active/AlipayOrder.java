package com.abc.active;

import org.apache.dubbo.common.extension.Activate;

// @Activate的order属性默认为0，order属性越小，其优先级越高
// @Activate的group属性与value属性均是用于给当前扩展类添加的标识
// group是一个“大范围标识”，value为一个“小范围标识”。
// 一个扩展类一旦指定了小范围标识value，那么这个大范围标识就不再起作用了
@Activate(group = "online", value = "alipay")
// @Activate(group = "online", value = {"alipay","alipay1"})
public class AlipayOrder implements Order {
    @Override
    public String way() {
        System.out.println("---  使用支付宝支付  ---");
        return "支付宝支付";
    }
}


