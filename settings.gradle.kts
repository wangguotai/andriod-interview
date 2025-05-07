println("Gradle版本: ${gradle.gradleVersion}")
println("可用扩展: ${gradle.extensions.extensionsSchema}")
pluginManagement {
    includeBuild("./xshell/rn-root/node_modules/@react-native/gradle-plugin")
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
includeBuild("./xshell/rn-root/node_modules/@react-native/gradle-plugin")
