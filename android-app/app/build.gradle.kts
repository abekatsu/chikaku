import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// サーバーURLはリポジトリに含めない local.properties から読む。
// 未設定でもビルドは通り、アプリ内の「詳細設定」から実行時に上書きできる。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val defaultServerUrl: String = localProps.getProperty("chikaku.serverBaseUrl") ?: "https://chikaku.example.com/"

// リリース署名も同じく local.properties から読む。鍵そのものはリポジトリの外に置く。
//
// **4つすべてが揃っているときだけ署名する。** 欠けていれば未署名のまま出す。
// 署名鍵を持たない環境でも release ビルドの検証（R8・lintVitalRelease）が
// できるようにするため、ここで失敗させない。実機に入れる APK かどうかは
// ビルド後に apksigner で確かめる。
val releaseKeystore = localProps.getProperty("chikaku.keystoreFile")?.let(rootProject::file)
val releaseKeystorePassword: String? = localProps.getProperty("chikaku.keystorePassword")
val releaseKeyAlias: String? = localProps.getProperty("chikaku.keyAlias")
val releaseKeyPassword: String? = localProps.getProperty("chikaku.keyPassword")

val canSignRelease = releaseKeystore?.exists() == true &&
    !releaseKeystorePassword.isNullOrEmpty() &&
    !releaseKeyAlias.isNullOrEmpty() &&
    !releaseKeyPassword.isNullOrEmpty()

if (!canSignRelease) {
    logger.lifecycle(
        "chikaku: 署名鍵が未設定のため release ビルドは未署名になります " +
            "（local.properties の chikaku.keystoreFile 他を参照）",
    )
}

android {
    namespace = "com.damburisoft.chikaku.watch"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.damburisoft.chikaku.watch"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "DEFAULT_SERVER_BASE_URL", "\"$defaultServerUrl\"")
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // Play ストアではなく APK を直接配る前提なので、
                // 端末が検証に使う署名方式を明示しておく。
                // v1 は minSdk 26 では不要（v2 以降のみを使う）。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // play-services-base が古い fragment 1.1.0 を引き込むため明示的に引き上げる。
    // （Fragment 自体はこのアプリでは使っていない）
    implementation(libs.androidx.fragment)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
