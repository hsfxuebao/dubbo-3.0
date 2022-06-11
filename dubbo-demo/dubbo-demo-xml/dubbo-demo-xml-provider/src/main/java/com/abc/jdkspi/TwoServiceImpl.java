package com.abc.jdkspi;

public class TwoServiceImpl implements SomeService {

    @Override
    public void hello() {
        System.out.println("执行TwoServiceImpl的hello()方法");
    }
}
