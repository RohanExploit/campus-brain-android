// Standalone build on purpose. This project is NOT included from a root
// settings file, and no root settings file should be created: doing so would
// pull mobile/android (the Flutter app) into a composite build and put its
// working release at risk. The two apps share nothing but the brain.db schema.
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

rootProject.name = "campus-brain-kotlin"
include(":app")
