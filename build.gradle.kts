// Conservative, mutually-compatible versions. If you bump these, bump them
// together — AGP and the Kotlin plugin are fussy about each other.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.20" apply false
}
