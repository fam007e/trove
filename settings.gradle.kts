pluginManagement {
    repositories {
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
        google()
        mavenCentral()
    }
}

rootProject.name = "Trove"

include(":app")
include(":ui:widgets")
include(":ui:browse")
include(":core:fs")
include(":core:net")
include(":core:crypto")
include(":server:ftp")
include(":server:webdav")
include(":server:sftp")
include(":privilege")
include(":feature:archive")
include(":feature:image")
include(":feature:pdf")
include(":feature:tagger")
include(":feature:media")
