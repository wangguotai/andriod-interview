plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    id("com.facebook.react")
}

android {
    namespace = "com.mi.rncommon"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("release") {
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    api(project(":xshell:common"))

    // React Native dependencies (version managed by RNGP)
    implementation(libs.react.android)
    implementation(libs.hermes.android)
}

// RN 的 JS 工程位于 xshell/rn-root（不在 Gradle rootProject 的上一级），
// 需要显式告知 RNGP 去哪里找 package.json / react-native / codegen。
val rnRootDir = rootProject.layout.projectDirectory.dir("xshell/rn-root")
val privateReact = rootProject.extensions.getByType(
        com.facebook.react.internal.PrivateReactExtension::class.java
)
privateReact.root.set(rnRootDir)
privateReact.reactNativeDir.set(rnRootDir.dir("node_modules/react-native"))
privateReact.codegenDir.set(rnRootDir.dir("node_modules/@react-native/codegen"))

// React Native specific configuration
react {
    // Enable autolinking for libraries
    autolinkLibrariesWithApp()

    // 本模块没有需要 codegen 的 JS/TS 源码，指向一个存在的目录避免扫描到无关文件
    jsRootDir.set(layout.projectDirectory.dir("src/main"))
}