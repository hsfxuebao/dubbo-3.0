package com.abc.active;

import org.apache.dubbo.common.extension.Activate;

@Activate(group = {"online", "offline"}, order = 3)
public class CardOrder implements Order {
    @Override
    public String way() {
        System.out.println("---  使用银联卡支付  ---");
        return "银联卡支付";
    }
}
