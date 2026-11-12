plugins {
    kotlin("jvm") version "2.0.21"
    application
    id("com.gradleup.shadow") version "8.3.5"
}

repositories { mavenCentral() }

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
}


application { mainClass.set("robloxvr.MainKt") }

tasks.shadowJar {
    archiveBaseName.set("RobloxVRPatcher")
    archiveClassifier.set("")
    archiveVersion.set("")
}
