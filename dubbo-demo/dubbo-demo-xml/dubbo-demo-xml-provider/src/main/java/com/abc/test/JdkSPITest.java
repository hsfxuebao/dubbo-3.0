package com.abc.test;

import com.abc.jdkspi.SomeService;

import java.util.Iterator;
import java.util.ServiceLoader;

public class JdkSPITest {

    public static void main(String[] args) {
        // load()中放的是业务接口，其就相当于要加载的配置文件名
        ServiceLoader<SomeService> loader = ServiceLoader.load(SomeService.class);
        Iterator<SomeService> it = loader.iterator();
        while (it.hasNext()) {
            SomeService service = it.next();
            service.hello();
        }
    }
}
