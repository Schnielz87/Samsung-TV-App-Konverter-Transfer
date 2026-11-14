import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

sourceSets["main"].kotlin.srcDir("../shared-ui/src")

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

val appVersion = (findProperty("appVersion") as String?) ?: "1.0.0"

// Version für die Update-Prüfung ins Programm schreiben
val versionDir = layout.buildDirectory.dir("generated/version")
val writeVersion by tasks.registering {
    inputs.property("v", appVersion)
    outputs.dir(versionDir)
    doLast { versionDir.get().file("tvtransfer-version.properties").asFile.apply { parentFile.mkdirs(); writeText("version=$appVersion\n") } }
}
sourceSets["main"].resources.srcDir(writeVersion)

compose.desktop {
    application {
        mainClass = "de.tvtransfer.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "TV-App-Transfer"
            packageVersion = appVersion
            description = "Apps auf Samsung-Fernseher und Android-TV-Sticks übertragen"
            vendor = "TV-App-Transfer"
            // ermittelt mit ./gradlew :desktop:suggestRuntimeModules
            modules("java.instrument", "jdk.unsupported", "jdk.crypto.ec", "java.naming")
            windows {
                menuGroup = "TV-App-Transfer"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "5b3f0c9e-7a1d-4c55-9a7e-2f6c1d8e4b21"
                iconFile.set(project.file("icon.ico"))
            }
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    // Lokale Tests ohne Grafikkarte (z. B. virtueller Bildschirm)
    System.getenv("SKIKO_RENDER_API")?.let { systemProperty("skiko.renderApi", it) }
}
