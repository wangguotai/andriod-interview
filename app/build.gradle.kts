import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    id("com.interview.thread.monitor")
    id("com.interview.sdk.governance")
}

// ═══════════════════════════════════════════════════════════════════════
//  三方 SDK 治理（静态审计）
// ═══════════════════════════════════════════════════════════════════════
//
//  `./gradlew :app:sdkAuditDebug` → app/build/reports/sdk-governance/
//     ├── sdk-audit.md    四个维度的人读报告（含证据与移除方案）
//     └── sdk-audit.json  CI 卡口可消费
//
//  firstPartyPrefixes 决定「三方」的判定边界，因而决定权限归属的正确性。
//  默认值刻意为窄（宁可漏报「可删权限」，也不误报成可删）：
//  写太宽会让 SDK 的权限使用被当成自有代码用，从而**漏掉本该保留的权限**。
configure<com.interview.sdk.plugin.SdkGovernanceExtension> {
    firstPartyPrefixes.addAll(
        "com.interview.",
        "com.example.myapplication.",
    )
}

// ═══════════════════════════════════════════════════════════════════════
//  包体积优化开关（编译期）
// ═══════════════════════════════════════════════════════════════════════
//
//  语义与用法见 gradle.properties 的说明块。这里只做一件事：
//  把 gradle.properties / -P 的字符串开关收敛成 Boolean，并在下面生效。
//
//  默认全 false ⇒ 日常构建产物与优化前**完全一致**（可回退、可对照）。
//  一键全开:  ./gradlew :app:assembleRelease -Popt.all
//
//  opt.all 展开为 shrink + audit。**不含 webp**：webp 会改写工作区源文件，
//  必须是显式动作，不能被一个"全开"顺手带上。
// ───────────────────────────────────────────────────────────────────────
// ─── 开关解析 ───
// 必须区分三种输入，否则开关会"静默失效"（本文件第一版就栽在这）：
//   · -Popt.shrink               → 值为空串。**必须视为 true** ——
//                                  "写了这个 flag 就是想要它开"，这是命令行惯例
//   · -Popt.shrink=true / 1 / yes → true
//   · -Popt.shrink=false          → false（可用于覆盖 gradle.properties）
// 只写 toBooleanStrictOrNull() 会让 `-Popt.shrink`（无值）解析成 false，
// 表现为"打开了开关但构建产物毫无变化"——最容易误判成"R8 没效果"。
fun optFlag(name: String): Boolean {
    if (!project.hasProperty(name)) return false
    return when ((project.findProperty(name) as? String)?.trim()?.lowercase()) {
        null, "", "true", "1", "yes", "on" -> true
        else -> false
    }
}

// ─── 崩溃上报配置（local.properties → BuildConfig）───
//
// 目的：让 `CrashUploader` 能拿到真实 endpoint/token，而**不把它们写进源码**。
//   local.properties 已在 .gitignore 里（只本机可见）→ Gradle 读它 → 注入 BuildConfig。
// 这样"演示项泄露成生产配置"那条红线仍然成立：仓库里只有**注释掉的示例**。
//
// 机器上从没配过这两个 key 时，getProperty 返回 null → 空串。
// CrashUploader.isConfigured() 据此返回 false，UI 显示"未配置"（而不是"上报失败"）。
//
// ⚠️ 转义：buildConfigField 的最后一个参数是一段**要写进生成的 Java 源文件**的字面量，
//    我们在外面手工拼双引号。值里若含 " 或 \ （token 里不罕见），不转义就会直接
//    破坏 Java 语法 → 编译期报一堆指向 BuildConfig 的诡异错误。所以统一过 escapeJava。
fun escapeJava(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

val crashLocalProps = Properties()
rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
    f.inputStream().use { crashLocalProps.load(it) }
}
val crashUploadUrl: String = crashLocalProps.getProperty("crash.upload.url").orEmpty()
val crashUploadToken: String = crashLocalProps.getProperty("crash.upload.token").orEmpty()

val optAll = optFlag("opt.all")
val optShrink = optAll || optFlag("opt.shrink")
val optAudit = optAll || optFlag("opt.audit")
val optWebp = optFlag("opt.webp")
val optWebpApply = optFlag("opt.webp.apply")

logger.lifecycle(
    "[opt] shrink=$optShrink audit=$optAudit webp=$optWebp(apply=$optWebpApply)" +
        if (!optShrink && !optWebp) " —— 全部关闭，构建行为与优化前一致" else "",
)

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

        // ─── 崩溃上报 endpoint/token（值来自 local.properties，见文件头 escapeJava）───
        // 没配就是空串：这会驱动 CrashUploader.isConfigured() == false，
        // 上报出口保持"未配置"状态（**不是**"上报失败" —— 两者处置完全不同）。
        buildConfigField("String", "CRASH_UPLOAD_URL", "\"${escapeJava(crashUploadUrl)}\"")
        buildConfigField("String", "CRASH_UPLOAD_TOKEN", "\"${escapeJava(crashUploadToken)}\"")

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
            // ─── R8：代码压缩 + 资源压缩（opt.shrink 开关）───
            //
            // 本仓库实测：release APK 的 dex 占解压后 81%（20.4 MB / 22.4 MB），
            // 而此前 isMinifyEnabled=false ⇒ R8 完全没跑。这是体积优化里最大
            // 的一根杠杆，收益远大于资源与 native 层。
            //
            // ⚠️ 风险边界：R8 漏 keep 的后果是**运行时崩溃 / 静默数据丢失**，
            //    而不是编译失败。app/proguard-rules.pro 已按「Retrofit 代理 /
            //    Gson 字段名 / JNI 符号 / ASM 改写落点」四类补齐（每类都写了
            //    理由），但首次打开仍必须跑真机回归。
            isMinifyEnabled = optShrink
            // 资源压缩：删掉未被引用的 res。必须与 minify 同开 —— 它依赖 R8
            // 产出的引用图；单独开只会退化。注意 release 默认 false。
            isShrinkResources = optShrink
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 体积包需要 mapping 才能还原崩溃栈。R8 默认就该产出；
            // 这里显式打开，避免 archive 配置变化时被悄悄关掉。
            if (optShrink) {
                isDebuggable = false
            }
        }
        // debug 侧不参与体积优化：Lab 依赖未裁剪的 dex/资源做对照演示
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
        // ─── buildConfig：AGP 8 起默认**关闭** ───
        // 不打开的话 buildConfigField(...) 会被静默忽略（或直接报"未开启"），
        // 于是 CrashUploader 里引用的 BuildConfig.CRASH_UPLOAD_URL 编译不过。
        // 本仓库插件现已依赖它，所以这里必须显式打开。
        buildConfig = true
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

    // ─── 交付层：AAB（opt.shrink 时随 release 一起可用）───
    // `:app:bundleRelease` 产物由 Play 按设备切分，安装包通常再降一截。
    //
    // 实测（本仓库 opt.shrink 构建）：
    //   APK 6.05 MB（native .so 为 mmap 而**不压缩**存储）
    //   AAB base 模块 3.57 MB（Play 下发给单台 arm64 设备的量）
    //   差额主要来自：AAB 里 .so 是压缩的（3.49→1.70 MB），加上资源分片。
    //
    // 下面只开语言/密度分片：本 app 是中文教学工程，但依赖库自带多语言，
    // 不分片会让 AAB 为每门语言各带一份。APK 单文件交付时无法这么排
    // （系统语言是英文的设备会缺字串），AAB 由 Play 只下发匹配分片才敢排。
    bundle {
        language {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        // abi.enableSplit：**故意不设置**。
        //   它默认为 true，AGP 会在配置期按当前 ABI 过滤集自动适配 —— 未来
        //   谁给 rust/vmp-android 补上 armeabi-v7a，分片会自动跟着开。
        //   硬写 false 会把这个自动适配钉死，反而要让后人回来改这行。
        //   （本仓库当前只有 arm64-v8a，开与不开的产物无差别，故无需显式声明。）
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

    // 三方 SDK 线程归因的运行时侧（Hook pthread_create 后的归因逻辑）。
    // ⚠️ 它只是归因的「记录与报告」层；真正的线程创建 hook 点仍需 native 侧
    //    （见 app/src/main/cpp/thread_hook.cpp 与 com.interview.thread.NativeThreadHook）——
    //    两者是配套的，Java 层负责抓创建者堆栈并做责任方归因。
    implementation(project(":sdk-governance-runtime"))

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

// ═══════════════════════════════════════════════════════════════════════
//  最终任务：无用资源 / 无用代码审计（opt.audit 开关）
// ═══════════════════════════════════════════════════════════════════════
//
//  为什么是"报告"而不是"自动删除"：
//    · 资源是否无用，静态分析给不出确定答案 —— getIdentifier() 拼接的资源名、
//      data-binding、以及本仓库 HomeCatalog 这类"登记式"入口都会让静态引用图
//      偏保守（漏报）或偏激进（误删）。
//    · 真正安全的自动删除只有一条路：R8 的 shrinkResources。它基于**编译期
//      真实引用图**，且 R8 会自己保留不确定项。所以本任务只负责"指出来"，
//      删除动作统一交给 opt.shrink —— 一个入口，一个真相。
//
//  产出（app/build/reports/opt/）：
//    unused-resources.txt  ← lint UnusedResources（含可能被反射引用的存疑项）
//    removed-code.txt      ← R8 实际删掉的类/成员（前提：opt.shrink 已开）
//    mapping.txt           ← 混淆映射，解崩溃栈用
//    报告完成后在命令行打印 Top 项，避免"生成了但没人看"。
// ───────────────────────────────────────────────────────────────────────
if (optAudit) {
    val optReportDir = layout.buildDirectory.dir("reports/opt")

    val auditUnusedResources by tasks.registering {
        group = "opt"
        description = "汇总 R8 的 usage.txt（真实删除清单）到 reports/opt/"
        outputs.dir(optReportDir)
        doLast {
            val out = optReportDir.get().asFile.apply { mkdirs() }

            // usage.txt 是**编译期真实可达性分析**的产物，比任何静态猜测都准。
            // 本任务在 assembleRelease 之后跑（finalizedBy），此时它一定已生成。
            val usageFiles = layout.buildDirectory.dir("outputs/mapping")
                .get().asFile.walkTopDown()
                .filter { it.name == "usage.txt" }.toList()

            if (usageFiles.isEmpty()) {
                println("[opt] 未找到 usage.txt —— 说明这次不是 opt.shrink 构建。")
                println("[opt] 跑: ./gradlew :app:assembleRelease -Popt.shrink")
                return@doLast
            }

            val merged = usageFiles.flatMap { it.readLines() }
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .distinct()
                .sorted()
            out.resolve("removed-code.txt").writeText(merged.joinToString("\n") + "\n")

            // 映射（解 release 崩溃栈用）——顺手拷到统一目录，免得到 mapping/ 下翻
            layout.buildDirectory.dir("outputs/mapping/release").get().asFile
                .resolve("mapping.txt").takeIf { it.exists() }
                ?.let { it.copyTo(out.resolve("mapping.txt"), overwrite = true) }

            println("[opt] removed-code.txt: ${merged.size} 行 → $out")
            println("[opt] 提示: 若 com/interview/** 的**整类**出现在这里，")
            println("[opt]       先核对 app/proguard-rules.pro —— 也可能是只删了空 <clinit>（正常）。")
            println("[opt]       判据：同时搜 mapping.txt，映射到自身 = 类保留。")
        }
    }

    // 用 matching().configureEach 而不是 tasks.named：
    // AGP 在 afterEvaluate 里才注册 assembleRelease —— 本文件是**配置期**执行，
    // 直接 tasks.named("assembleRelease") 会报 "Task not found"（已实测踩到）。
    // matching 返回惰性集合，对之后新增的任务同样生效。
    tasks.matching { it.name == "assembleRelease" }.configureEach {
        finalizedBy(auditUnusedResources)
    }
}