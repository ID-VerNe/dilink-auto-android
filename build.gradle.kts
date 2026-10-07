plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
    // Compose Desktop（JetBrains 版，仅 :app-desktop 使用）。1.6.2 对应 Kotlin 1.9.22。
    id("org.jetbrains.compose") version "1.6.2" apply false
}
