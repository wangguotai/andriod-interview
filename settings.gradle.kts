println("Gradle版本: ${gradle.gradleVersion}")
println("可用扩展: ${gradle.extensions.extensionsSchema}")
pluginManagement {
    includeBuild("./xshell/rn-root/node_modules/@react-native/gradle-plugin")
    includeBuild("./build-logic")
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven ("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        // 国内无法使用google源，使用上面的阿里云镜像替代
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
//        google()
//        mavenCentral()
        gradlePluginPortal()
    }

}
plugins {
    id("com.facebook.react.settings")
}

println("WGT === TEST ===")
val myRnFile = settings.layout.rootDirectory.dir("./xshell/rn-root").asFile

extensions.configure<com.facebook.react.ReactSettingsExtension> {

    println(myRnFile)
    autolinkLibrariesFromCommand(workingDirectory = myRnFile)
//    autolinkLibrariesFromCommand()
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven ("https://maven.aliyun.com/repository/public/")
        maven ("https://maven.aliyun.com/repository/central")
        google()
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "My Application"
include(":app")
include(":scroll-event-demo")
include(":xshell")
include(":xshell:login")
include(":xshell:order")
include(":xshell:common")
include(":xshell:mrouter-annotations")
include(":xshell:mrouter-compiler")
include(":xshell:mrouter-api")
include(":xshell:rnCommon")
include(":thread-lint")
include(":ipc-lab")
include(":vmp-core")
include(":vmp-lab")
include(":sdk-governance-runtime")
// 插件模块：**刻意不进主 App 的依赖图**（:app 的 dependencies 里没有它）。
// 它被 include 的唯一目的是让 AGP 负责"编译"这一步（Kotlin + R.java + resources.arsc），
// 打包成可分发插件则由 tools/plugin-package/package.sh 手工完成 —— 见那个脚本的注释。
include(":plugin-demo")
includeBuild("./xshell/rn-root/node_modules/@react-native/gradle-plugin")
