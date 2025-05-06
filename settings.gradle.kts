
pluginManagement {
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
