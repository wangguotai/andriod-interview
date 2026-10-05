// RemoteInfo.aidl —— 跨进程传输的结构体。
//
// `parcelable` 关键字让 AIDL 编译器找到同名的 Java 类（RemoteInfo.java），
// 由它负责 writeToParcel / CREATOR。把这些「元信息」跨进程带回来，是为了让每条
// demo 证据都能自证：「这条结果确实来自 pid=x、tid=y 的**另一个进程**」。

package com.interview.ipc.binder;

parcelable RemoteInfo;
