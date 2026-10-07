import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

/**
 * :vmp-lab —— VMP 加固实验台（面试演示用）。
 *
 * ─── 为什么是一个独立 module，而不是往 app 里塞 ───
 *
 * 与 `:ipc-lab` 同一套理由，但这里还多两条：
 *
 * 1. **加固产物必须能与未加固产物并存、同时对拍**。本 Lab 的 native 侧产出的是
 *    一个**独立**的 `.so`（`libvmp_android.so`，由 `rust/vmp-android` 交叉编译），
 *    不替换 app 既有的 `libimagepipeline.so`。独立 module 让「装不装、卸不卸」互不影响 ——
 *    加固实验失败绝不能波及正在用的图片加载实验。
 * 2. **它自带 LAUNCHER，是一个可单独安装的 Demo App**（applicationId
 *    `com.interview.vmplab`），与 `:ipc-lab` / `:scroll-event-demo` 一致。
 *    主 app 侧零改动。
 *
 * ─── 包名 ───
 * `com.interview.vmp` 与其它 Lab 的包名风格一致（`com.interview.*`）。
 * JNI 符号名因此是 `Java_com_interview_vmp_VmpNative_*` —— 与
 * `rust/vmp-android/src/android_impl.rs` 里写死的符号必须**逐字符一致**，
 * 改包名/改类名都要同时改那边。
 */
android {
    namespace = "com.interview.vmp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.interview.vmplab"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // native 只编 arm64-v8a：与 app 侧同一取舍（教学场景下真机与 arm64 模拟器都够，
        // 四 ABI 会显著拖慢构建）。Kotlin 侧对 .so 缺失做降级，见 VmpBridge。
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
        viewBinding = true
        buildConfig = true
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
            // 不继承 shell 里导出的 RUSTUP_HOME/CARGO_HOME。从 local.properties 读，
            // 其次取环境变量。不传的后果：cargo 找不到，CMake 只打 warning 就跳过 Rust，
            // 构建「成功」但 APK 里没有 .so —— 最容易被当成假绿的坑。
            cmake {
                val localProps = Properties()
                rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
                    f.inputStream().use { localProps.load(it) }
                }
                val rustupHome: String? = localProps.getProperty("rustup.home") ?: System.getenv("RUSTUP_HOME")
                val cargoHome: String? = localProps.getProperty("cargo.home") ?: System.getenv("CARGO_HOME")
                if (!rustupHome.isNullOrBlank()) arguments("-DRUSTUP_HOME_VALUE=$rustupHome")
                if (!cargoHome.isNullOrBlank()) arguments("-DCARGO_HOME_VALUE=$cargoHome")
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
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.org.jetbrains.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
