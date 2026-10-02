// Android target of a shared/ module; applied by averyn.kmp-library unless AVERYN_BACKEND_ONLY is set.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        // shared/<name> -> dev.averyn.shared.<name>
        namespace = "dev.averyn.shared.${project.name.replace('-', '_')}"
        compileSdk = 37
        minSdk = 26
    }
}
