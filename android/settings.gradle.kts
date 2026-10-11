pluginManagement {
    repositories {
        google()
        // Miroir Google Cloud Storage de Maven Central — évite les 429
        // de repo.maven.apache.org lors des résolutions intensives.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "teleprompteur"
include(":app")
