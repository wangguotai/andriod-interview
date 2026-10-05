import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

/**
 * :ipc-lab —— 跨进程通信（IPC）实验台。
 *
 * ─── 为什么是一个独立 module，而不是往 app 里塞 ───
 *
 * IPC 演示需要**第二个进程**（`android:process=":ipc_remote"`），还带 AIDL / Provider /
 * 原生 .so 一整套。放在 app 里就要动 app 的 Manifest 与 native 构建配置，风险外溢到
 * 与 IPC 无关的既有实验。独立 module 把「多进程」「AIDL」「Rust .so」这些影响面收在
 * 自己的构建脚本里，app 侧零改动（只在 HomeCatalog 加一个入口跳转）。
 *
 * ─── 包名 ───
 * `com.interview.ipc` 与其它 Lab 的包名风格保持一致（com.interview.*）。
 * applicationId 独立成 `com.interview.ipclab`，便于单独安装/卸载而不影响主 app。
 */
android {
    namespace = "com.interview.ipc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.interview.ipclab"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // native 只编 arm64-v8a：教学场景下真机与 arm64 模拟器都够，四 ABI 会显著拖慢构建。
        // 与 app 侧的取舍一致（见 ImagePipelineBridge 的注释）。
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
        // AIDL 默认开启（src/main/aidl 下的 .aidl 会被编译）；这里显式写出便于阅读。
        aidl = true
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
