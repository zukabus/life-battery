plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.jenil.lbp2900"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.jenil.lbp2900"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-untested"
    }

    buildTypes {
        // Debug builds are signed with the standard debug key, which is
        // what makes the CI artifact installable without any setup.
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation(project(":capt-core"))
}
