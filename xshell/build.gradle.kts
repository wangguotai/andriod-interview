apply(from = "config.gradle")
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    id("kotlin-kapt")
}
println("build xshell module")

// 从配置文件中获取全局的配置信息
val appID: Map<String, String> by extra
val androidID: Map<String, String> by extra
val url: Map<String, String> by ext
val isRelease: Boolean by ext

android {
    namespace = appID["app"].toString()
//    namespace = "com.example.xshell"
    compileSdk = (extra["androidID"] as Map<*, *>)["compileSdkVersion"] as Int

    defaultConfig {
        applicationId = (extra["appID"] as Map<*, *>)["app"].toString()
//        applicationId = "com.example.xshell"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
//        https://www.cnblogs.com/linghu-java/p/13934992.html
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 传递参数 当app包名发生变化，可以动态的告知 注解处理器
        kapt {
            arguments {
                arg("wgt", "hello javapoet")
            }
            // 不适用编译过程中的缓存，避免异常情况
            useBuildCache = false
        }
    }

    buildFeatures {
        buildConfig = true
    }
    buildTypes {
        debug {
            buildConfigField("String", "debug", "\"${url["debug"]}\"")
            buildConfigField("Boolean", "isRelease", "$isRelease")
        }
        release {
            buildConfigField("String", "release", "\"${url["release"]}\"")
            buildConfigField("Boolean", "isRelease", "$isRelease")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(project(":xshell:login"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(project(":xshell:common"))
    if (isRelease) {
        // 依附app壳
        implementation(project(":xshell:login"))
        implementation(project(":xshell:order"))
    } else {
        // 不能依附，因为 login 模块能独立运行
    }
    // 依赖注解
    implementation(project(":xshell:mrouter-annotations"))
    // 使用自定义的注解处理器
    kapt(project(":xshell:compiler"))
}