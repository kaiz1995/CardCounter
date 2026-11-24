plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.cardcounter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.cardcounter"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
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
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    testImplementation("junit:junit:4.13.2")
    // 桌面 JVM 版，与手机端同版本：单测用它加载同一个 onnx，验证与 Python 评测逐框一致。
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.32.0")
    // JVM 单测使用真实 org.json 实现（Android SDK 的 org.json 在本地单测中未 mock）
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
