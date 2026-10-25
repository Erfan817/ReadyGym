import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.readygym.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.readygym.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "0.8.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        val configured = providers.gradleProperty("readyGymServiceBaseUrl")
            .orElse(providers.environmentVariable("READY_GYM_SERVICE_BASE_URL")).orElse("").get()
        fun str(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
        val literal = str(configured)
        // Debug defaults to the dev machine as seen from the emulator (10.0.2.2).
        val debugUrl = if (configured.isBlank()) "\"http://10.0.2.2:8080\"" else literal

        // Optional direct AI gateway (OpenAI-compatible). The key lives in the gitignored
        // local.properties (readyGymAi*) or READY_GYM_AI_* env vars, so it never enters git; an
        // empty key builds an app whose coach chats through the ReadyGym server's AI proxy instead.
        val local = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.isFile) f.inputStream().use { load(it) }
        }
        fun aiSetting(name: String, env: String): String =
            (providers.gradleProperty(name).orElse(providers.environmentVariable(env))
                .orElse(providers.provider { local.getProperty(name) ?: "" }).get()).trim()
        val aiBase = str(aiSetting("readyGymAiBaseUrl", "READY_GYM_AI_BASE_URL"))
        val aiModel = str(aiSetting("readyGymAiModel", "READY_GYM_AI_MODEL").ifBlank { "deepseek-chat" })
        val aiKey = str(aiSetting("readyGymAiApiKey", "READY_GYM_AI_API_KEY"))
        // PGYER update-check keys (账户设置→API 信息 / 应用→安装设置); blank disables the check.
        val pgyerApiKey = str(aiSetting("readyGymPgyerApiKey", "READY_GYM_PGYER_API_KEY"))
        val pgyerAppKey = str(aiSetting("readyGymPgyerAppKey", "READY_GYM_PGYER_APP_KEY"))

        getByName("debug") {
            buildConfigField("String", "SERVICE_BASE_URL", debugUrl)
            buildConfigField("String", "AI_BASE_URL", aiBase)
            buildConfigField("String", "AI_MODEL", aiModel)
            buildConfigField("String", "AI_API_KEY", aiKey)
            buildConfigField("String", "PGYER_API_KEY", pgyerApiKey)
            buildConfigField("String", "PGYER_APP_KEY", pgyerAppKey)
        }
        getByName("release") {
            // Minified + resource-shrunk for distribution; signed with the debug key because this
            // app is handed out directly (no Play Store), and the debug keystore is reproducible
            // on this machine. Configure your own release signing before distributing your build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "SERVICE_BASE_URL", literal)
            buildConfigField("String", "AI_BASE_URL", aiBase)
            buildConfigField("String", "AI_MODEL", aiModel)
            buildConfigField("String", "AI_API_KEY", aiKey)
            buildConfigField("String", "PGYER_API_KEY", pgyerApiKey)
            buildConfigField("String", "PGYER_APP_KEY", pgyerAppKey)
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
