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

rootProject.name = "SuperAwesomeMagicalEmail"

// shared: the API's models, used by the server and the Android app, so both agree on the wire.
// server: the self-hosted server and its web app (web/, built into the jar).
// android: the phone app, which signs in to the server. It needs the Android SDK; the server's
// container image is built without it (SAME_SERVER_ONLY=1).
include(":shared", ":server")
if (System.getenv("SAME_SERVER_ONLY") == null) include(":android")
