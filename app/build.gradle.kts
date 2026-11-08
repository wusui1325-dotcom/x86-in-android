import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun loadKeystoreProperties(): Properties {
    val props = Properties()
    val candidates = mutableListOf<File>()
    System.getenv("X86_KEYSTORE_PROPS")?.takeIf { it.isNotBlank() }?.let { candidates += file(it) }
    candidates += file("/workspace/.secrets/x86-in-android/keystore.properties")
    candidates += rootProject.file("keystore.properties")
    candidates += rootProject.file("keystore/keystore.properties")
    for (f in candidates) {
        if (f.exists()) {
            FileInputStream(f).use { props.load(it) }
            break
        }
    }
    return props
}

val keystoreProperties = loadKeystoreProperties()

val versionJson = rootProject.file("version.json").readText()
val appVersionName = Regex("\"versionName\"\\s*:\\s*\"([^\"]+)\"").find(versionJson)!!.groupValues[1]
val appVersionCode = Regex("\"versionCode\"\\s*:\\s*(\\d+)").find(versionJson)!!.groupValues[1].toInt()

android {
    namespace = "com.wusper.x86"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wusper.x86"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val envStore = System.getenv("ANDROID_KEYSTORE_PATH")
            val envPass = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            val envAlias = System.getenv("ANDROID_KEY_ALIAS")
            val envKeyPass = System.getenv("ANDROID_KEY_PASSWORD")
            if (!envStore.isNullOrBlank() && !envPass.isNullOrBlank() && !envAlias.isNullOrBlank()) {
                storeFile = file(envStore)
                storePassword = envPass
                keyAlias = envAlias
                keyPassword = if (!envKeyPass.isNullOrBlank()) envKeyPass else envPass
            } else {
                val storePath = keystoreProperties.getProperty("storeFile")
                if (!storePath.isNullOrBlank()) {
                    storeFile = file(storePath)
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                        ?: keystoreProperties.getProperty("storePassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

android.applicationVariants.configureEach {
    val variant = this
    outputs.configureEach {
        val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        out.outputFileName = if (variant.buildType.name == "release") {
            "x86-in-android-release.apk"
        } else {
            "x86-in-android-${variant.buildType.name}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
}

// 可選：./gradlew bumpVersion  （正式一鍵請用 scripts/release.sh）
tasks.register("bumpVersion") {
    group = "versioning"
    description = "Bump versionCode (+1) and versionName patch via scripts/bump_version.sh"
    doLast {
        exec {
            commandLine("bash", rootProject.file("scripts/bump_version.sh").absolutePath)
        }
    }
}
