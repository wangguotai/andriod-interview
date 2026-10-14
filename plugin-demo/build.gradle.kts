/**
 * :plugin-demo —— **不进主 App** 的插件模块。
 *
 * ─── 它的定义性特征只有一条：宿主 apk 里没有它的任何字节 ───
 *
 * 判定标准（可执行、可证伪）：
 *   · `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep PluginEntry` → 空
 *   · 反过来 `d8` 只把这个 module 的 classes 编成 `plugin-demo-debug.apk`，
 *     由 `tools/plugin-package/package.sh` 组装，落在宿主 `assets/` 或私有目录。
 *
 * ─── 为什么是 android library 而不是自己调 kotlinc + d8 ───
 *
 * 编译链需要 AGP 才能拿到 `R.java` / `resources.arsc` / merged manifest 与
 * `android.jar` 的正确 classpath。自己拼命令行等于把 AGP 的一半重写一遍，
 * 且每升一次 compileSdk 就得修一次。
 *
 * 换句话说：**用 AGP 编译，但不用 AGP 打包**。产物的 dex 由 d8 单独出、
 * 资源由我们自己的脚本裁掉多余 chunk 后重签名 —— 这一层是 AGP 不给的。
 *
 * 注意：本 module **不进 settings.gradle.kts 的 include 列表之外的任何引用**。
 * :app 的 `dependencies {}` 里**绝不能**出现 `implementation(project(":plugin-demo"))`，
 * 那是把插件塞回宿主、让整个演示失去意义的唯一方式（也会被 `checkDebugDuplicateClasses`
 * 或 HomeCatalog 的编译期引用抓到）。
 */
plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

android {
    namespace = "com.interview.plugindemo"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        // 插件不带 versionName 也得能编译；打包脚本从 aapt2 的 badging 里读
        // （library 模块的 badging 里 versionName 是 null，故这里显式给一个）。
        buildConfigField("String", "PLUGIN_VERSION_NAME", "\"1.0.0\"")
    }

    buildTypes {
        // 插件不参与 R8：本演示要的就是「未裁剪的 dex 与资源」，好让
        // 「宿主里没有、插件里有」这件事能用 javap/unzip 直接看出来。
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // ═══════════════════════════════════════════════════════════════
    //  共享面（Shared API）与 root 级文件：必须**编进插件**，但不依赖 :app
    // ═══════════════════════════════════════════════════════════════
    //
    // 用 srcDir 而不是 `implementation(project(":app"))`，原因有两条且都很硬：
    //
    //  1. 语义：插件不该依赖宿主。project 依赖会让 AGP 生成「:plugin-demo 依赖
    //     :app 的 classes」这条边，编译出来的 dex 会带上 app 的全部类 ——
    //     "独立打包"当场破产，还极容易配成循环依赖。
    //  2. 运行期仍由宿主提供定义：插件 dex 里即使编进了 IPluginPage，运行期
    //     PluginClassLoader 对 com.interview.* 也会**先委派宿主**，于是拿到的是
    //     宿主那一份 Class 对象 ⇒ 跨 ClassLoader 的类型转换才能成立。
    //     详见 app/src/main/java/com/interview/plugin/contract/IPluginPage.kt。
    //
    // ⚠️ 只共享 contract 这一个目录，**不要**顺手把 app/src/main/java 整个挂进来：
    //    那会把 app 的全部源码编进插件 dex，等于把宿主复制一份。
    sourceSets.getByName("main") {
        java.srcDir(rootProject.file("app/src/main/java/com/interview/plugin/contract"))
        // ⚠️ assets 与 res 都是**并集**语义，不是覆盖：srcDir 只是再加一个来源目录，
        //    不会顶掉本 module 自己的 src/main/assets 与 src/main/res。
        //    这一点很关键 —— plugin-root-probe.txt 要落进 apk 根目录（而非 assets/），
        //    而又不能弄丢自己已有的 assets/ 与 res/。
        assets.srcDir("src/main/pluginRoot")
    }

    // 只对 debug 变体做一次即可；本项目插件不区分 debug/release 内容。
    androidResources {
        // 插件资源**不做**资源压缩/重命名：本演示要的是"宿主解析不到、插件解析得到"
        // 这条可读性证据，资源名被 R8 改名后报告会难看很多。
        // （注意：nameShrinking 只在 release + isMinifyEnabled 时才有意义，此处显式关掉
        //   是为了让_intent_清晰，不是修 bug。）
        noCompress += listOf("plugin-root-probe.txt")
    }

    buildFeatures {
        buildConfig = true
        // 不开了：viewBinding 生成的绑定类会多出一层「宿主也认识」的类型，
        // 对演示反射/隔离没有帮助，反而让 dex 更大。
        viewBinding = false
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // ═══════════════════════════════════════════════════════════════
    //  故意为空 —— 这一节是「插件独立」的第一条硬约束
    // ═══════════════════════════════════════════════════════════════
    //
    // 插件 dex 里一旦带上第三方类（哪怕只是 kotlinx-coroutines），宿主在
    // DexClassLoader 之外就得再有第二条类路径，否则运行期 NoClassDefFoundError。
    // 保持零依赖 = 插件 dex 只需要 `android.jar` + 宿主里的 **共享接口**。
    //
    // ⚠️ 但「零声明依赖」不等于「零传递依赖」：kotlin-stdlib 由 kotlin 插件
    //    自动带上（implementation）。这是唯一被允许的例外，理由是宿主自己也是
    //    Kotlin 且**必须**用同一个 stdlib 实现共享接口 —— 两者在同一 ClassLoader
    //    里就是同一份类。打包脚本会把 plugin dex 里的 kotlin/* 统计出来写进报告，
    //    不隐藏这件事。
    //
    //   ⚠️ 反例：想给插件加 appcompat 就必须重新回答「谁提供它」，
    //      答案通常不是「打包进插件」，而是「宿主暴露一个接口」。
}
