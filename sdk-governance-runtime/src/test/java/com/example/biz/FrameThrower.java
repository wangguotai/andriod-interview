package com.example.biz;

/**
 * 测试专用的「业务代码」替身。
 *
 * ⚠️ 存在的理由：归因过滤器的规则之一是**跳过工具自身的帧**
 * （前缀 `com.interview.sdk.runtime.thread`）。而测试类恰好就在那个包下，
 * 所以「在测试类里 new Throwable()」得到的首个业务帧**一定会**被过滤掉，
 * 断言就测不到真正想测的行为（「业务帧不被过滤」）。
 *
 * 把抛栈点放到一个中立的业务包里，断言才有意义 —— 这也顺带验证了
 * 「过滤器只认前缀，不会误伤其他包」这一前提。
 */
public final class FrameThrower {

    private FrameThrower() {}

    /** 在业务包内创建一个 Throwable，使其堆栈顶部的业务帧落在此包。 */
    public static Throwable throwHere() {
        return new Throwable("biz-site");
    }
}
