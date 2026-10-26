plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
    // Compose Desktop（JetBrains 版，仅 :app-desktop 使用）。1.6.2 对应 Kotlin 1.9.22。
    id("org.jetbrains.compose") version "1.6.2" apply false
}

// Build revision: <short sha>[-dirty] — computed once here at root
// configuration so app-client 与 vd-server 两边的 BuildConfig.BUILD_REVISION
// 同源（见 issue #8）。纯 JDK ProcessBuilder，与 Gradle API 版本无关；
// 无 git 时回退 "unknown"，构建照常进行，日志只会显示 +gunknown。
extra["buildRevision"] = try {
    fun runGit(vararg cmd: String): String =
        ProcessBuilder(*cmd).redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim()
    val sha = runGit("git", "rev-parse", "--short", "HEAD")
    val dirty = runGit("git", "status", "--porcelain")
    sha + if (dirty.isNotEmpty()) "-dirty" else ""
} catch (_: Exception) {
    "unknown"
}
