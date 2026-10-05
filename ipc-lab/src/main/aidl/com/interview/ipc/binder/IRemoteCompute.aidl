// IRemoteCompute.aidl —— Lab 的 AIDL 主线接口。
//
// 「AIDL 四件套」里它代表最核心的一类：**双向方法调用**。
// 客户端拿到 IRemoteCompute 的代理（Proxy），调用方法时 Binder 驱动把参数
// 序列化成 Parcel 送到远端进程，远端 Stub 反序列化后执行、再把结果序列化回来。
//
// ─── 方法分类（正好覆盖 Binder 的三种调用语义）───
//   - 同步短调用：add / echo（调用方线程阻塞等待结果）
//   - 观察者注册：registerCallback / unregisterCallback（把客户端 Binder 反向传给服务端）
//   - oneway 异步：computeAsync（服务端不阻塞返回，结果靠回调送回）
//
// 回调参数 `IRemoteCallback` 本身也是 Binder：这就是「双向 Binder」——
// 服务端能拿它回调客户端，也是 linkToDeath 演示里死亡通知的载体。

package com.interview.ipc.binder;

import com.interview.ipc.binder.IRemoteCallback;
import com.interview.ipc.binder.RemoteInfo;

interface IRemoteCompute {

    /** 远端进程自身信息（pid / uid / 进程名 / 当前 binder 线程 tid）。 */
    RemoteInfo getRemoteInfo();

    /** 同步加法规约：证明「参数去、结果回」这条最基本链路。 */
    int add(int a, int b);

    /** 同步回显：证明字符串跨进程传输。 */
    String echo(String input);

    /**
     * 一次「有耗时」的调用的当前线程信息。客户端用多线程并发调用它，
     * 收集返回的 tid 集合 —— 用于证明 Binder 服务端是**线程池**而非单线程。
     */
    RemoteInfo probeCallingThread(int holdMillis);

    /** 注册回调（客户端 Binder 反向传给服务端）。 */
    void registerCallback(IRemoteCallback callback);

    /** 注销回调。 */
    void unregisterCallback(IRemoteCallback callback);

    /**
     * 异步计算：立即返回，结果/进度通过 [computeAsync] 传入的回调送回。
     * 会在回调里逐步上报进度，用来观察「oneway 不阻塞调用方」。
     */
    oneway void computeAsync(int input, IRemoteCallback callback);

    /**
     * 让远端进程自杀，用于演示客户端侧 linkToDeath 的死亡通知。
     * ⚠️ 会杀掉整个 :ipc_remote 进程；后续演示会由系统重新拉起新进程。
     */
    void crashRemoteProcess();
}
