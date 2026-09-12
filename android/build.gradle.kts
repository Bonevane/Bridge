plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Compose compiler ships with Kotlin from 2.0 on, so the versions must match.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
