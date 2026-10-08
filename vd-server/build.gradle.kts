plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dilinkauto.vdserver"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // vd-server had NO test source set at all, which left the encode hot path
    // (frame header layout, writeAll deadline, adaptive bitrate) completely
    // unguarded. Added so DRY-3 / SRP-4 can be done with a regression net.
    testImplementation("junit:junit:4.13.2")
}
