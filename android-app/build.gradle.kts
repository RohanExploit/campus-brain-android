// AGP version matches mobile/android, which is already green in this repo's CI.
// Kotlin is supplied by AGP 9 itself, so no Kotlin plugin is declared here.
plugins {
    id("com.android.application") version "9.1.0" apply false
}
