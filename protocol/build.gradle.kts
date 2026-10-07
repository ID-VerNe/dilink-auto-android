plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dilinkauto.protocol"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
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
    // 平台无关的协议核心（api：上游模块可直接使用 FrameCodec / Messages / Connection 等）
    api(project(":protocol-core"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
