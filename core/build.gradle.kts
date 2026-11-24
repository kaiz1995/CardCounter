plugins {
    id("org.jetbrains.kotlin.jvm")
}

// 纯 Kotlin 账本、存储与识别核心：手机和电脑离线评测共用同一份代码。
// 本模块的编译路径上没有 Android SDK，写进 android.* 会直接编译失败。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 手机上由系统自带 org.json；电脑上由使用方（:eval、单测）提供实现。
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}