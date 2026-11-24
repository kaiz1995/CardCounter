plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// 电脑上的整局离线回放与评测，直接调用 :core 里和手机相同的识别、状态机与账本代码。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.example.cardcounter.eval.OfflineEvalMainKt")
}

// `gradlew :eval:run` 的默认工作目录是 eval/，但命令行里的 datasets/... 都相对仓库根写。
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

dependencies {
    implementation(project(":core"))
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
