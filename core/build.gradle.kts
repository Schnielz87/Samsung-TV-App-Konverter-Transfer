import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // SSH für LG webOS (Entwicklermodus: Installation über ssh/sftp wie LGs ares-cli)
    implementation("com.github.mwiede:jsch:0.2.20")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
