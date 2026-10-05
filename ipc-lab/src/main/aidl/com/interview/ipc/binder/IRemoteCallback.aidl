// IRemoteCallback.aidl —— 服务端 → 客户端 的回调接口（双向 Binder 的「反向」那一半）。
//
// `oneway`：所有方法都是「发了就返回」，服务端不等待客户端处理完。
// 这正是 Binder 里「同步调用 vs 单向调用」的分界 —— oneway 不占用服务端 binder 线程等待，
// 因此适合高频进度/事件上报。

package com.interview.ipc.binder;

import com.interview.ipc.binder.RemoteInfo;

oneway interface IRemoteCallback {

    /** 进度上报：percent 0..100，stage 为人类可读阶段名。 */
    void onProgress(int percent, String stage);

    /** 最终结果：连同「结果是在哪个远端线程算出来的」一起送回。 */
    void onResult(in RemoteInfo info, String payload);

    /** 出错上报。 */
    void onError(String message);
}
