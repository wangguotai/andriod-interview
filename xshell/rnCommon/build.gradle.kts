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

// React Native specific configuration
react {
    // Enable autolinking for libraries
//    autolinkLibrariesWithApp.set(true)
    autolinkLibrariesWithApp()

    // Optional configuration examples:
    // root.set(file("../../"))
    // reactNativeDir.set(file("../../node_modules/react-native"))
    // codegenDir.set(file("../../node_modules/@react-native/codegen"))
}