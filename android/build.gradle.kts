import com.unciv.build.AndroidImagePacker
import com.unciv.build.BuildConfig
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // [추가] Firebase 구글 서비스 플러그인 - 이 줄이 있어야 google-services.json을 인식합니다
    id("com.google.gms.google-services")
}

android {
    compileSdk = 36
    sourceSets {
        getByName("main").apply {
            manifest.srcFile("AndroidManifest.xml")
            java.srcDirs("src")
            aidl.srcDirs("src")
            renderscript.srcDirs("src")
            res.srcDirs("res")
            assets.srcDirs("assets")
            jniLibs.srcDirs("libs")
        }
    }
    packaging {
        resources.excludes += "META-INF/robovm/ios/robovm.xml"
        resources.excludes += "DebugProbesKt.bin"
    }
    defaultConfig {
        namespace = BuildConfig.identifier
        applicationId = BuildConfig.identifier
        minSdk = 21
        targetSdk = 35
        versionCode = BuildConfig.appCodeNumber
        versionName = BuildConfig.appVersion

        base.archivesName.set("Unciv")
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_1_8
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("android/debug.keystore")
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storePassword = "android"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
            isDebuggable = false
        }
    }

    lint {
        disable += "MissingTranslation"
    }
    compileOptions {
        targetCompatibility = JavaVersion.VERSION_1_8
        // [중요] Firebase는 최신 자바 기능을 쓰기 때문에 이 설정이 필수입니다
        isCoreLibraryDesugaringEnabled = true
    }
    androidResources {
        ignoreAssetsPattern = "!SaveFiles:!fonts:!maps:!music:!mods"
    }
    buildFeatures {
        renderScript = true
        aidl = true
    }
}

tasks.register("texturePacker") {
    doFirst {
        logger.info("Calling TexturePacker")
        AndroidImagePacker.packImages(projectDir.path)
    }
}

tasks.register("copyAndroidNatives") {
    val natives: Configuration by configurations

    doFirst {
        val rx = Regex(""".*natives-([^.]+)\.jar$""")
        natives.forEach { jar ->
            if (rx.matches(jar.name)) {
                val outputDir = file(rx.replace(jar.name) { "libs/" + it.groups[1]!!.value })
                outputDir.mkdirs()
                copy {
                    from(zipTree(jar))
                    into(outputDir)
                    include("*.so")
                }
            }
        }
    }
    dependsOn("texturePacker")
}

tasks.whenTaskAdded {
    if ("package" in name || "assemble" in name || "bundleRelease" in name) {
        dependsOn("copyAndroidNatives")
    }
}

private fun getSdkPath(): String? {
    val localProperties = project.file("../local.properties")
    return if (localProperties.exists()) {
        val properties = Properties()
        localProperties.inputStream().use { properties.load(it) }

        properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    } else {
        System.getenv("ANDROID_HOME")
    }
}

tasks.register<Exec>("run") {
    standardOutput = System.out
    errorOutput = System.err
    isIgnoreExitValue = false

    val path = getSdkPath()
    val adb = "$path/platform-tools/adb"

    commandLine(adb, "shell", "am", "start", "-n", "com.unciv.app/AndroidLauncher")
}

dependencies {
    // 기존 안드로이드 기본 라이브러리
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // [추가] Firebase 라이브러리 실장
    // 최상단 build.gradle.kts에 적어준 classpath와 연동되어 작동합니다.
    implementation(platform("com.google.firebase:firebase-bom:33.0.0"))
    implementation("com.google.firebase:firebase-database-ktx")
}
