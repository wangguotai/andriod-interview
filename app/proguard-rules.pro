# ═══════════════════════════════════════════════════════════════════════
#  R8 keep 规则 —— 仅在 -Popt.shrink=true 时生效
# ═══════════════════════════════════════════════════════════════════════
#
#  纪律（与 app/lint.xml 一致）：每一条 keep 都要写明**为什么需要**。
#  "先加上回头再说"会让规则池失控，最后没人敢删 —— 那等于没开 R8。
#
#  判断标准：删除后**不会**因"编译不过"而暴露的，才需要 keep。
#  以下六类是本仓库实读代码后确认的必要项：

# ─────────────────────────────────────────────────────────────────────
# 1. Retrofit 接口（运行时动态代理）
#
# Retrofit 用动态代理实现接口，方法名 → HTTP 路径的映射发生在**运行时**，
# 由注解 + 泛型签名解析。R8 会删掉"看起来没人调用"的接口方法并抹掉签名。
#   · ShareReflectUtil.kt 里还有 getDeclaredField/getDeclaredMethod 的反射，
#     目标字段/方法名一旦被混淆就抛 NoSuchFieldException（运行时）。
#
# 只保签名与注解、不保方法体，体积代价很小。
# ─────────────────────────────────────────────────────────────────────
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# 本仓库的 Service 接口（com.interview.retrofit.GitHub）与 Gson 模型（Contributor）
#
# ⚠️ 必须写 `class` 而不是 `interface` —— 这条被实测抓过：
#    只写 `-keep interface com.interview.retrofit.**` 时，`Contributor`
#    （data class）匹配不上，R8 把它**整类删除**（可在 usage.txt 中搜到），
#    字段名 login/contributions 也从 dex 消失。后果是 Gson 反序列化
#    静默失败（接口 200、界面空白），编译期毫无提示。
#    `-keep class` 同时覆盖 class / interface / enum，是这里的正确粒度。
-keep class com.interview.retrofit.** { *; }
-keep class retrofit2.** { *; }
-keep class retrofit2.adapter.rxjava3.** { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ─────────────────────────────────────────────────────────────────────
# 2. Gson 数据模型（反射读写字段）
#
# converter-gson 用反射按**字段名**匹配 JSON key。字段被重命名 = 解析出 null，
# 且**不报错**（最阴的一类：接口返回正常，界面空白）。
#   · @SerializedName 已固定的，可以只保注解；但本仓库从 GitHub API 反序列化，
#     未加 @SerializedName 的字段依赖原名，故整包保住。
#   · 若将来模型都显式标注 @SerializedName，可把下面收窄为
#     -keepclassmembers,allowobfuscation —— 届时请同步改这里。
# ─────────────────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keep,allowobfuscation @interface com.google.gson.annotations.SerializedName
-dontwarn com.google.gson.**

# ─────────────────────────────────────────────────────────────────────
# 3. JNI —— native 方法与其持有类
#
# 这是**最不能漏**的一类：JNI 符号名由「包名 + 类名 + 方法名」拼成
# （如 Java_com_interview_net_nativebridge_NetLabNative_abiVersion），
# 链接发生在运行时 dlopen 之后。类名/方法名被混淆 → UnsatisfiedLinkError，
# 且**编译期完全无感**。
#
# 本仓库的 native 桥接点 —— 逐个核对过（外部函数所在的类，均实读确认）：
#   · com.interview.anrsignal.AnrSigquitHook        → libanrsignal.so
#   · com.interview.net.nativebridge.NetLabNative   → libnetlab_android.so   (Rust)
#   · com.interview.image.nativebridge.ImagePipelineNative → libimagepipeline_android.so (Rust)
#   · com.interview.memtrace.MemTraceNative         → libmemtrace.so  (bytehook)
#   · com.interview.vmp.nativebridge.VmpNative      → libvmp_android.so (Rust, 来自 :vmp-core)
#   · com.interview.thread.NativeThreadHook         → libthreadhook.so
#   · com.interview.nativecrash.NativeCrashHandler  → libnativecrash.so
#   · com.interview.oomdump.OomSnapshotNative        → liboomsnapshot.so
#
# ⚠️ 上面这一行（NativeCrashHandler）有一条**实测教训**必须留档：
#
#     第一版曾把 JNI 声明类放在中文包 `com.interview.稳定性监控` 里，并在 C++ 侧
#     `extern "C"` 直接声明带 UTF-8 中文包段的符号。clang 宽容地编译过了、
#     `llvm-nm` 也能看到导出符号 —— 但**运行期必挂**：
#
#       UnsatisfiedLinkError: No implementation found for
#         ...NativeCrashHandler.installNative(...)
#       (tried Java_com_interview__07a33_05b9a_06027_076d1_063a7_NativeCrashHandler_installNative ...)
#
#     ART 查符号时把类名里的**每个非 ASCII 字节按 JNI 规范转义成 `_0XX`**，
#     而 clang 导出的是**原始 UTF-8 字节** —— 两者永远对不上。能编译 ≠ 能链接。
#     （这与 `AnrSigquitHook.kt:12-26` 早就写明的结论一致。）
#
#     ⇒ JNI 边界类必须放 ASCII 包（`com.interview.nativecrash`），
#       业务类留在中文包，由 `NativeCrashCollector` 隔一跳转发。
# ─────────────────────────────────────────────────────────────────────
#
# ⚠️ -keepclasseswithmembernames 只对**存活类**生效：R8 若先把整个类判定为
#    不可达并删掉，规则也救不回来。所以下面逐个显式 -keep 这些桥接类 ——
#    写错一个包名 = 真机 UnsatisfiedLinkError，且编译期完全无感。
#    （改这些包名时，这里必须同步改；建议连带跑一次 release 真机冒烟。）
# ─────────────────────────────────────────────────────────────────────
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.interview.anrsignal.AnrSigquitHook { *; }
-keep class com.interview.net.nativebridge.** { *; }
-keep class com.interview.image.nativebridge.** { *; }
-keep class com.interview.memtrace.** { *; }
-keep class com.interview.vmp.nativebridge.** { *; }
-keep class com.interview.nativecrash.** { *; }
-keep class com.interview.oomdump.** { *; }
# thread 包整体保住：既含 NativeThreadHook（JNI），又含 ASM 改写的落点（见第 4 节）
-keep class com.interview.thread.** { *; }

# ─────────────────────────────────────────────────────────────────────
# 4. ThreadMonitor 编译期 ASM 改写的落点
#
# build-logic/thread-plugin 把 `new Thread` 的 owner 换成 UnifiedThread，
# 并且在字节码里**插入字符串常量**（ThreadClassVisitor.kt:200
# currentClass.replace('/', '.')）。插入的字面量是类名，R8 看不到其用途。
#   · UnifiedThread/ThreadPools/ThreadDefense 是收口层本体，名字必须稳定
#     —— 混淆后崩溃栈无法归因到本仓库，thread-lint 的路径匹配也会失准。
# ─────────────────────────────────────────────────────────────────────
-keep class com.interview.thread.UnifiedThread { *; }
-keep class com.interview.thread.ThreadPools { *; }
-keep class com.interview.thread.ThreadDefense { *; }

# ─────────────────────────────────────────────────────────────────────
# 5. 反射 / 框架契约
# ─────────────────────────────────────────────────────────────────────
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}

# 枚举的 values()/valueOf() 常走反射，R8 会误判为未使用
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable CREATOR：Android 框架按名字反射读取
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# 保留行号：release 崩溃栈需要可读。想极致瘦身可注释掉
# （届时崩溃栈只剩混淆后行号，靠 mapping.txt 还原）。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ─────────────────────────────────────────────────────────────────────
# 6. 已知无害的缺类警告（不写出规则会刷屏，掩盖真正的问题）
# ─────────────────────────────────────────────────────────────────────
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn kotlinx.**
