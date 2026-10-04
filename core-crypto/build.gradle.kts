plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core-common"))
    implementation(libs.acinq.bitcoin.kmp)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.acinq.secp256k1.jni.jvm)
}
