package com.interview.dynamicProxy;

import java.lang.reflect.Proxy;

interface IService { void doWork(); }
class RealService implements IService {

    @Override
    public void doWork() {
        System.out.println("实际工作...");
    }
}

public class ProxyDemo {
    public static void main(String[] args) {
        IService realService = new RealService();
        IService proxy = (IService) Proxy.newProxyInstance(
                realService.getClass().getClassLoader(),
                new Class[]{IService.class},
                (proxyObj, method, args1)-> {
                    long start = System.currentTimeMillis();
                    Object result = method.invoke(realService, args1);
                    System.out.println("方法耗时："+ (System.currentTimeMillis() - start) + "ms");
                    return result;
                }
        );
    }
}
