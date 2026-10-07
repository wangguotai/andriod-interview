/**
 * :vmp-lab —— VMP 加固实验台（可单独安装的 Demo App）。
 *
 * ─── 本 module 现在几乎什么都不做了 ───
 *
 * 实现（native 交叉编译、JNI、VmpBridge、VmpLabActivity、布局）全部搬到了
 * [:vmp-core]，因为主 app 的首页也要用它 —— `HomeCatalog` 只接受**本 app 内**的
 * Activity（`Class<out Activity>`），独立 APK 挂不上去，所以 `:app` 必须自己承载
 * 这份功能。共享成 library 之后，两边是**同一份**代码，不存在「首页那份和 Lab
 * 那份行为不一样」这种漂移。
 *
 * 本 module 保留的只有两件事，而且都是「宿主 / 测试」职责：
 *
 * 1. **LAUNCHER 与 Manifest**：可单独安装、可单独启动。这是这个 Lab 的原有价值 ——
 *    加固实验失败时不该波及正在用的主 app；
 * 2. **androidTest**：金标准对拍与基准。放在这里而不是 library 里，是因为它们需要
 *    「装一个带 instrumentation 的 APK」，搁在 library 会随依赖传染给 :app。
 *    而 :vmp-lab 本来就是个可安装的 App，测试归它最自然。
 *
 * ─── 为什么保留而不是直接删掉 ───
 * 「能单独装、单独跑、单独测」是这个加固实验的隔离前提。合进主 app 会让
 * native 构建失败直接影响日常使用的 APK —— 那正是当初做成独立 module 要避免的事。
 */
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

android {
    // 注意：namespace 与 :vmp-core 共用 `com.interview.vmp` 是**可以**的 ——
    // 两者是不同 module，R 类不冲突，Kotlin 包也不冲突。好处是 Activity 的
    // 全限定名在两边完全一致，Manifest 与首页登记都用同一个字符串。
    // 唯一的实际影响：AndroidManifest 里的 Activity 名必须写全限定名而不是 `.ui.X`，
    // 因为 namespace 变了（`.ui.X` 会解析成 com.interview.vmplab.ui.X，那是错的）。
    namespace = "com.interview.vmplab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.interview.vmplab"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // :vmp-core 已经把 native 限成 arm64-v8a（见那边的注释），这里不重复声明 ——
        // 重复声明会让「限制来自哪一层」变得难查。真实限制由 library 决定，
        // 并会传染给所有宿主（含 :app）。
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // 不需要 viewBinding：本 module 已经没有任何布局（都随 UI 搬到了 :vmp-core）。
    // 留一个开着但没布局的开关会让人以为这里还有界面代码。

    ndkVersion = "25.1.8937393"
}

dependencies {
    // 全部实现来自这里。UI / 桥接 / native 都在 :vmp-core。
    implementation(project(":vmp-core"))

    // ⚠️ androidTest 需要**显式**再声明一次 :vmp-core。
    // `implementation` 不传递到测试的编译类路径，而这里的测试直接引用
    // VmpBridge / VmpNative —— 少这一行会以「Unresolved reference」的编译错误出现，
    // 还算好查；真正坑的是运行期依赖缺失（NoClassDefFoundError）那种形态。
    androidTestImplementation(project(":vmp-core"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
