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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.recyclerview)
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