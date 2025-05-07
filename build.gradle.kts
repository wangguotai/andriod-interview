// Top-level build file where you can add configuration options common to all sub-projects/modules.
//buildscript {
//    dependencies {
//        classpath("com.android.tools.build:gradle")
//        classpath("com.facebook.react:react-native-gradle-plugin")
//        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin")
//    }
//}

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.jetbrainsKotlinAndroid) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.org.jetbrains.kotlin.jvm) apply false
    id("com.facebook.react.rootproject")
}