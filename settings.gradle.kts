pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
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
include(":mylibrary")
include(":scroll-event-demo")
include(":xshell")
include(":xshell:login")
include(":xshell:order")
include(":xshell:common")
include(":xshell:mrouter-annotations")
include(":xshell:compiler")
