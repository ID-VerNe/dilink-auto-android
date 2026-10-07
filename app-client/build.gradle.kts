import java.net.URLClassLoader
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dilinkauto.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dilinkauto.client"
        minSdk = 29
        targetSdk = 34
        versionCode = project.property("app.versionCode").toString().toInt()
        versionName = project.property("app.versionName").toString()
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    val releaseKeystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    if (releaseKeystorePassword != null && releaseKeyPassword != null) {
        signingConfigs {
            create("release") {
                storeFile = file(System.getenv("RELEASE_KEYSTORE_FILE") ?: "dilink-auto-release.keystore")
                storePassword = releaseKeystorePassword
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "dilinkauto"
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Apply release signing to both debug and release when env vars are available.
    // This ensures users can install any build over another without signature conflicts.
    signingConfigs.findByName("release")?.let { releaseConfig ->
        buildTypes.getByName("release").signingConfig = releaseConfig
        buildTypes.getByName("debug").signingConfig = releaseConfig
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("dev.mobile:dadb:1.2.10")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

// Build the VD server JAR and copy to assets before the client APK is assembled.
// vd-server is a proper Gradle module — compilation handled by AGP/Kotlin.
// This task just runs d8 + jar packaging.
tasks.register("buildVdServer") {
    dependsOn(":vd-server:bundleLibRuntimeToJarDebug")
    // :protocol-core 是纯 JVM 模块（PlatformLog 等协议基元都在这里）。Phase 0 把它
    // 从 :protocol 拆出后，下面那份 inputs 曾漏掉它 —— 编译期正常、d8 也正常，直到
    // app_process 启动 vd-server 时才以 NoClassDefFoundError: PlatformLog 崩掉，
    // 且因为崩在最早一行日志之前，vd-server.log 完全空白，极难定位。
    dependsOn(":protocol-core:jar")

    val vdBuildDir = file("${rootDir}/vd-server/build/tmp/vds-d8")
    val vdClassesJar = file("${rootDir}/vd-server/build/intermediates/runtime_library_classes_jar/debug/classes.jar")
    val protocolJar = file("${rootDir}/protocol/build/intermediates/runtime_library_classes_jar/debug/classes.jar")
    val protocolCoreJar = file("${rootDir}/protocol-core/build/libs/protocol-core.jar")
    val d8Jar = file("${android.sdkDirectory}/build-tools/${android.buildToolsVersion}/lib/d8.jar")
    val assetsDir = file("src/main/assets")

    // Kotlin stdlib + coroutines (needed at runtime by vd-server via app_process)
    val kotlinLibs = project.configurations.detachedConfiguration(
        project.dependencies.create("org.jetbrains.kotlin:kotlin-stdlib:1.9.22"),
        project.dependencies.create("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3")
    ).resolve()

    outputs.upToDateWhen { false }

    doLast {
        vdBuildDir.deleteRecursively()
        vdBuildDir.mkdirs()

        // Clean stale artifacts from assets
        file("${assetsDir}/vd-server.dex").delete()

        // DEX vd-server + protocol + protocol-core + kotlin stdlib (needed for app_process runtime)
        val inputs = listOf(vdClassesJar.absolutePath, protocolJar.absolutePath, protocolCoreJar.absolutePath) +
            kotlinLibs.map { it.absolutePath }
        val d8Args = (listOf("--output", vdBuildDir.absolutePath) + inputs).toTypedArray()

        val d8ClassLoader = URLClassLoader(arrayOf(d8Jar.toURI().toURL()), javaClass.classLoader)
        try {
            val d8Class = d8ClassLoader.loadClass("com.android.tools.r8.D8")
            val mainMethod = d8Class.getMethod("main", Array<String>::class.java)
            mainMethod.invoke(null, d8Args)
        } finally {
            d8ClassLoader.close()
        }

        // Rename to vd-server.dex (delete old first — renameTo fails silently on Windows if dest exists)
        val classesDex = file("${vdBuildDir}/classes.dex")
        val serverDex = file("${vdBuildDir}/vd-server.dex")
        serverDex.delete()
        if (!classesDex.renameTo(serverDex)) {
            classesDex.copyTo(serverDex, overwrite = true)
            classesDex.delete()
        }

        // Create JAR (ZIP containing vd-server.dex as classes.dex)
        val jarFile = file("${vdBuildDir}/vd-server.jar")
        jarFile.delete()
        ZipOutputStream(FileOutputStream(jarFile)).use { zos ->
            zos.putNextEntry(ZipEntry("classes.dex"))
            serverDex.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }

        // Copy to phone app assets only (car deploys it over USB-ADB)
        assetsDir.mkdirs()
        jarFile.copyTo(file("${assetsDir}/vd-server.jar"), overwrite = true)

        println("VD server JAR built: ${jarFile.length()} bytes -> assets")
    }
}

// Embed the car (server) APK in client assets so the phone can auto-install it on the car
tasks.register("embedServerApk") {
    dependsOn(":app-server:assembleDebug")
    val serverApk = file("${rootDir}/app-server/build/outputs/apk/debug/app-server-debug.apk")
    val assetsDir = file("src/main/assets")

    outputs.upToDateWhen { false }

    doLast {
        assetsDir.mkdirs()
        val target = file("${assetsDir}/app-server.apk")
        if (serverApk.exists()) {
            serverApk.copyTo(target, overwrite = true)
            println("Server APK embedded: ${target.length()} bytes")
        } else {
            println("WARNING: Server APK not found at ${serverApk.absolutePath}")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("buildVdServer", "embedServerApk")
}
