import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    id("com.interview.thread.monitor")
}

configure<com.interview.thread.plugin.ThreadMonitorExtension> {
    enableNaming.set(true)
    enableUnify.set(true)
    excludedPackages.addAll(
        "com.interview.thread.plugin.",
        "com.interview.thread.UnifiedThread",
        "com.interview.thread.ThreadPools",
        "com.interview.thread.ThreadDefense",
    )
}

android {
    namespace = "com.example.myapplication"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // ─── ABI 只保留 arm64-v8a ───
        //
        // 起因：`:vmp-core` 引入了 VMP 加固的 Rust 产物（libvmp_android.so），
        // 而 `rust/vmp-android` 只编 arm64-v8a（教学场景够用，四 ABI 会显著拖慢
        // cargo 交叉编译）。ABI 限制会**从 library 传染到宿主**，所以这里必须显式收口。
        //
        // 影响面（写清楚，避免以后忘了这是谁带来的）：
        //   · 本仓库其余 native（imagepipeline / netlab / threadhook）也一起只剩 arm64；
        //   · 真机（arm64）与 arm64 模拟器不受影响；
        //   · 32 位设备（armeabi-v7a / x86）将**装不上**这个 APK。
        //     若将来要支持，做法是给 rust/vmp-android 补上对应 rustup target，
        //     再删掉这一行 —— rust_build.cmake 本身已支持四个 ABI。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        // ─── prefab：把依赖 AAR 里的预编译 .so 暴露给 CMake ───
        // memtrace（native 内存分配归因）依赖 bytehook 的 .so，而 bytehook 以 AAR
        // 分发（.so 放在 AAR 的 prefab/ 目录）。不打开这个开关，CMake 侧
        // `find_package(bytehook)` 会直接报「找不到包」，且错误信息指向 CMake
        // 而不是依赖 —— 是那种会浪费半小时的错。
        prefab = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // ── 把 -Dmem.realHprof=<path> 透传给**测试 JVM** ──
            // 为什么需要：真实 hprof dump（本项目实测 45 MB）不能进版本库，
            // 但解析器**必须**在真实 dump 上验证过 —— 手写的迷你 hprof 只能覆盖
            // "我想到的情形"，真实文件的 9893 个 segment 才会覆盖"我没想到的"。
            // （第一版解析器正是被真实 dump 抓出来的：ArrayIndexOutOfBounds。）
            // Gradle 的 -D 只作用于 Gradle 自身，不会自动进测试 JVM，所以这里显式透传。
            // 不传时该测试自行跳过，对日常构建零影响：
            //   ./gradlew :app:testDebugUnitTest --tests "*RealDumpTest*" \
            //       -Dmem.realHprof=/path/to/dump.hprof
            all { test ->
                System.getProperty("mem.realHprof")?.let {
                    test.systemProperty("mem.realHprof", it)
                }
            }

            // JVM 单测里 android.jar 的方法默认抛「not mocked」。设 true 后返回默认值
            // （Log.v 返回 0、Looper 等返回 null），从而让**依赖 android.util.Log 的
            // 生产代码**也能在宿主 JVM 上被测到 —— 否则测试就得为「能不能打日志」
            // 而绕过真实实现，那反而是本末倒置。
            isReturnDefaultValues = true
        }
    }

    externalNativeBuild {
        // 顶层 cmake 块只负责 path/version（AGP 8.3 的顶层类型没有 arguments）。
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        externalNativeBuild {
            // 把 Rust 工具链位置传给 CMake。
            //
            // 为什么必须显式传：Gradle 调起的 CMake 是独立进程，不会继承我们 shell 里
            // 导出的 RUSTUP_HOME/CARGO_HOME。实测不传的后果是：cargo 找不到，
            // CMake 侧只打一条 warning 就跳过 Rust —— 构建「成功」、APK 里却没有 .so，
            // 正是那种最容易被当成假绿的坑。
            //
            // 位置从 local.properties 读（不入库、机器相关），其次取环境变量。
            // 本机 Rust 在 /Volumes/ext/Rust/{rustup,cargo}。
            // 注意必须用 arguments(...) 方法而不是 arguments.add(...)：后者在
            // Kotlin DSL 的 delegate 上解析不到。
            cmake {
                val localProps = Properties()
                rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
                    f.inputStream().use { localProps.load(it) }
                }
                val rustupHome: String? =
                    localProps.getProperty("rustup.home") ?: System.getenv("RUSTUP_HOME")
                val cargoHome: String? =
                    localProps.getProperty("cargo.home") ?: System.getenv("CARGO_HOME")
                if (!rustupHome.isNullOrBlank()) {
                    arguments("-DRUSTUP_HOME_VALUE=$rustupHome")
                }
                if (!cargoHome.isNullOrBlank()) {
                    arguments("-DCARGO_HOME_VALUE=$cargoHome")
                }
            }
        }
    }

    ndkVersion = "25.1.8937393"

    lint {
        // 豁免清单（收口层自身 / 反面教材 / 需要裸线程的诊断）
        lintConfig = file("lint.xml")

        // 线程治理规则强制为 error，不允许被降级为 warning
        error += listOf("NewThreadUsage", "ExecutorsThreadPool", "HandlerThreadUsage")

        xmlReport = true
        htmlReport = true

        // ─── CI 卡口模式 ───
        // 本仓库存在与线程无关的既有 lint 债务（如 OnClick 回调缺失），
        // 直接跑完整 lint 会让线程问题淹没在噪声里，卡口形同虚设。
        // 用 -PthreadLintOnly 只跑线程规则，作为**可立即落地**的聚焦闸门：
        //     ./gradlew :app:lintDebug -PthreadLintOnly
        if (project.hasProperty("threadLintOnly")) {
            checkOnly += listOf("NewThreadUsage", "ExecutorsThreadPool", "HandlerThreadUsage")
        }
    }
}

dependencies {

    // 自定义 Lint 规则（NewThreadUsage / ExecutorsThreadPool / HandlerThreadUsage）
    // 装在 lintChecks 上 → 随 lintDebug/lintRelease 执行，可卡在 CI
    // Kotlin DSL 没有 lintChecks 顶层访问器，用 add() 形式
    add("lintChecks", project(":thread-lint"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    // MockWebServer：集成测试里提供一个**真实的 OkHttp 网络栈**（本地）。
    // 用来证明「应用拦截器合成 Response 时 EventListener 仍会触发 callEnd」这条
    // 会发生双记的路径确实存在，以及本仓库的防双记护栏真的生效 ——
    // 这是纯逻辑单测证明不了的（它依赖 OkHttp 的真实拦截器链与事件时序）。
    testImplementation("com.squareup.okhttp3:mockwebserver:${libs.versions.okhttp.get()}")
    // okhttp-tls：让 MockWebServer 跑 **HTTPS**。必需 —— 路由判定会拒绝明文 http，
    // 用明文 mock server 根本走不到被测路径（会一路退回 OkHttp）。
    testImplementation("com.squareup.okhttp3:okhttp-tls:${libs.versions.okhttp.get()}")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.recyclerview)

    // VMP 加固 Lab 的实现层（Rust 虚拟机 + JNI + VmpBridge + VmpLabActivity）。
    // 抽成 library 而不是在 app 里再抄一份：主页入口要求 Activity 在本 app 内，
    // 但实现只能有一份 —— 否则修一个 bug 要改两处，漂移了也不会报错。
    implementation(project(":vmp-core"))

    // ── Native 内存分配归因（memtrace）的 hook 框架 ──
    // bytehook：字节跳动的 PLT/GOT hook 实现，以 **AAR + prefab** 分发预编译 .so。
    //
    // ⚠️ 版本**必须**是 1.0.10，不能升 1.1.x（实测卡出来的，不是偏好）：
    //    1.1.x 的 AAR metadata 里 minCompileSdk=37，而本仓库 compileSdk=34
    //    ⇒ Gradle 依赖解析直接失败，报「requires compileSdk 37」这种
    //    与内存监控毫无关系的错，容易误判成工程配置坏了。
    //    1.0.10 实测 minCompileSdk=1，且已带 prefab（含 header）与四个 ABI。
    //
    // 为什么不用自己写的 GOT Hook（本仓库 thread_hook.cpp 已有）：
    //   那个是「1 个符号 × 1 个模块」，而 malloc 归因要「4 个符号 × 所有模块」
    //   + 新加载 .so 的覆盖 + 递归/并发防护，且处在**分配热路径**上。
    //   详见 app/src/main/cpp/memtrace.cpp 文件头 §二 的对照表。
    implementation("com.bytedance:bytehook:1.0.10")
    // 图片加载 Lab：Glide 4.12（降采样教学对象）
    implementation(libs.com.github.bumptech.glide)
    // okhttp 直连：下载"全尺寸原图"用 —— Glide 内部也依赖它，此处显式声明避免隐式传递
    implementation(libs.com.squareup.okhttp3)
    // 核心LiveData库 (必需)
    implementation( libs.androidx.lifecycle.livedata)
    // Kotlin扩展 (推荐)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    testImplementation(libs.kotlinx.coroutines.test)

    // retrofit
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0") // GSON转换器
    // 可选扩展库
    implementation(libs.adapter.rxjava3)  // RxJava支持
    implementation(libs.logging.interceptor) // 网络日志
}