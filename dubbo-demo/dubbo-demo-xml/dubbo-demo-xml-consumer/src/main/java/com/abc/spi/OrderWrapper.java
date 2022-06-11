package com.abc.spi;

import org.apache.dubbo.common.URL;

public class OrderWrapper implements Order {
    private Order order;

    public OrderWrapper(Order order) {
        this.order = order;
    }

    @Override
    public String way() {
        System.out.println("before-OrderWrapper对way()的增强");
        String way = order.way();
        System.out.println("after-OrderWrapper对way()的增强");
        return way;
    }

    @Override
    public String pay(URL url) {
        System.out.println("before-OrderWrapper对pay()的增强");
        String pay = order.pay(url);
        System.out.println("after-OrderWrapper对pay()的增强");
        return pay;
    }
}
