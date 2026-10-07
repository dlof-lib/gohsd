plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val homeUrl: String = (project.findProperty("HOME_URL") as String?) ?: "https://openwrt.org"
val homeHost: String = java.net.URI(homeUrl).host ?: "localhost"
val hasKeystore: Boolean = System.getenv("KEYSTORE_FILE")?.let { File(it).exists() } == true

android {
    namespace = "com.gohsd.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gohsd.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.3.0"

        buildConfigField("String", "HOME_URL", "\"$homeUrl\"")
        buildConfigField("String", "HOME_HOST", "\"$homeHost\"")
        manifestPlaceholders["homeHost"] = homeHost
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(System.getenv("KEYSTORE_FILE")!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // بدون مفتاح توقيع حقيقي يُوقَّع بمفتاح debug ليبقى قابلاً للتثبيت
            signingConfig = if (hasKeystore) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        warningsAsErrors = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
