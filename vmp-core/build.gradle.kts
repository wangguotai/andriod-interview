import java.util.Properties

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

/**
 * :vmp-core —— VMP 加固的**共享实现层**（android library）。
 *
 * ─── 为什么要有这一层 ───
 *
 * 本仓库的首页（`HomeCatalog`）用 `Class<out Activity>` 登记入口，因此**只有本 app
 * 里的 Activity 才能挂上首页**，独立 APK（`:ipc-lab` / `:scroll-event-demo`）做不到。
 * 要把 VMP 放进首页，就必须让 `:app` 自己承载这份功能。
 *
 * 而「再抄一份进 app」是明确要避免的：VmpBridge / VmpLabActivity / JNI 桥接 /
 * 布局加起来几百行，两份之后修一个 bug 要改两处，而且**漂移了不会报错** ——
 * 只会表现为「首页那一份和 Lab 那一份行为不一样」。所以抽成 library，
 * `:app` 与 `:vmp-lab` 各自依赖，**零重复**。
 *
 * ─── 这一层放什么 ───
 *
 * · rust/vmp-android 的交叉编译接线（CMakeLists → libvmp_android.so）
 * · JNI 入口 `nativebridge/VmpNative.kt`
 * · 加固路径收口 `VmpBridge.kt`（字节层 + 位图层）
 * · 演示界面 `ui/VmpLabActivity.kt` 与它的布局/图标/主题
 *
 * **不放**享用的是 androidTest：那些测试需要「装一个带 instrumentation 的 APK」，
 * 放在 library 里会随依赖关系传染给 :app。它们留在 :vmp-lab —— 那里本来就是一个
 * 自带 LAUNCHER 的可安装 Demo，测试归它最自然。
 *
 * ─── 命名空间 ───
 * `com.interview.vmp` 与原来一致，因此 JNI 符号名
 * `Java_com_interview_vmp_nativebridge_VmpNative_*` **不需要任何改动** ——
 * 这一点很重要：JNI 符号没有编译期检查，改名只会在真机上炸成 UnsatisfiedLinkError。
 */
android {
    namespace = "com.interview.vmp"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        // native 只编 arm64-v8a：与 `rust/vmp-android` 的产物一致（也是原 :vmp-lab 的取舍）。
        // ⚠️ 这条限制会**向上传染**给依赖它的 :app —— 见 NOTES 里「ABI 限制」一节：
        //    app 侧因此也只能出 arm64。真机（arm64）与 arm64 模拟器都不受影响，
        //    但 32 位设备会装不上。这是「把 native 加固代码放进主 app」的真实代价。
        ndk {
            abiFilters += "arm64-v8a"
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
        viewBinding = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        externalNativeBuild {
            // 把 Rust 工具链位置传给 CMake。Gradle 调起的 CMake 是独立进程，
            // 不继承 shell 里导出的 RUSTUP_HOME/CARGO_HOME。不传的后果是：
            // cargo 找不到，CMake 只打 warning 就跳过 Rust，构建「成功」但 APK 里
            // 没有 .so —— 最容易被当成假绿的坑。
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.org.jetbrains.kotlinx.coroutines.android)
}
