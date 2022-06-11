package com.abc.spi;

import org.apache.dubbo.common.URL;

public class OrderWrapper2 implements Order {
    private Order order;

    public OrderWrapper2(Order order) {
        this.order = order;
    }

    @Override
    public String way() {
        System.out.println("before-OrderWrapper222对way()的增强");
        String way = order.way();
        System.out.println("after-OrderWrapper222对way()的增强");
        return way;
    }

    @Override
    public String pay(URL url) {
        System.out.println("before-OrderWrapper222对pay()的增强");
        String pay = order.pay(url);
        System.out.println("after-OrderWrapper222对pay()的增强");
        return pay;
    }
}
