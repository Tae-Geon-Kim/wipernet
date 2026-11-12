plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
    buildTypes {
        // 공모전 제출용 release APK를 바로 설치할 수 있게 디버그 키로 서명한다(App Market 배포 때는 배포 키로 바꾼다).
        release { signingConfig = signingConfigs.getByName("debug") }
    }
    buildFeatures { compose = true }
    // 시뮬레이터와 같은 업로드 규칙 사례(C5)를 테스트에서 읽는다.
    sourceSets["test"].resources.srcDir("../../shared")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.datastore.preferences)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.fusedlocation)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
