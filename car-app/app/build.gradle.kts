plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.wipernet.car"
    compileSdk = 37
    defaultConfig {
        applicationId = "ai.wipernet.car"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.datastore.preferences)

    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.fusedlocation)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
