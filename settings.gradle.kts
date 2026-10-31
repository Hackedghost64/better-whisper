pluginManagement {
    repositories {
        google()
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

rootProject.name = "better-whisper"
include(":app")
include(":feature:bubble")
include(":core:audio")
include(":core:transcription")
include(":core:text-cleaning")
include(":core:intent")
