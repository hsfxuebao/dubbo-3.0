package org.apache.dubbo.demo;

public class GreetingServiceMock implements GreetingService {
    @Override
    public String hello() {
        return "This is mock result";
    }
}
