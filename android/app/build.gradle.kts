plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.mgelsinger.smsbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.mgelsinger.smsbridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
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
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES")
        }
    }
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            // Kotlin models are checked against the same fixtures as the schemas
            // and the desktop app.
            it.systemProperty("protocol.fixtures", rootProject.file("../protocol/fixtures").absolutePath)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.zxing.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.okhttp)
}
