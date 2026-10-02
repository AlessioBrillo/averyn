rootProject.name = "averyn"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// The backend Docker image has no Android SDK and no Xcode: it builds with AVERYN_BACKEND_ONLY=1.
if (System.getenv("AVERYN_BACKEND_ONLY") != null) {
    include(":shared:domain", ":shared:tracking", ":shared:metrics", ":backend")
} else {
    include(
        ":shared:domain",
        ":shared:tracking",
        ":shared:metrics",
        ":shared:sync",
        ":shared:ios-umbrella",
        ":backend",
        ":apps:android",
    )
}
