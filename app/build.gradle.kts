plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

fun configurationValue(name: String, defaultValue: String = ""): String =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: defaultValue

fun quoted(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val cloudBaseUrl = configurationValue(
    name = "EDGE_CLOUD_BASE_URL",
    defaultValue = "https://openrouter.ai/api/v1"
)
val cloudApiKey = configurationValue("EDGE_CLOUD_API_KEY")
val cloudModel = configurationValue(
    name = "EDGE_CLOUD_MODEL",
    defaultValue = "google/gemini-3.8-flash"
)
val typesafeApiKey = configurationValue("EDGE_TYPESAFE_API_KEY")
val geminiApiKey = configurationValue("EDGE_GEMINI_API_KEY")
val groqApiKey = configurationValue("GROQ_API_KEY")
val mcpEndpoint = configurationValue("EDGE_MCP_ENDPOINT")
val mcpToken = configurationValue("EDGE_MCP_BEARER_TOKEN")
val mcpEnabled = configurationValue(
    name = "EDGE_MCP_ENABLED",
    defaultValue = "false"
).toBooleanStrictOrNull() ?: false

android {
    namespace = "com.edgehybrid.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.edgehybrid.agent"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "CLOUD_BASE_URL", quoted(cloudBaseUrl))
        buildConfigField("String", "CLOUD_API_KEY", quoted(cloudApiKey))
        buildConfigField("String", "CLOUD_MODEL", quoted(cloudModel))
        buildConfigField("String", "TYPESAFE_API_KEY", quoted(typesafeApiKey))
        buildConfigField("String", "GEMINI_API_KEY", quoted(geminiApiKey))
        buildConfigField("String", "GROQ_API_KEY", quoted(groqApiKey))
        buildConfigField("String", "MCP_ENDPOINT", quoted(mcpEndpoint))
        buildConfigField("String", "MCP_BEARER_TOKEN", quoted(mcpToken))
        buildConfigField("boolean", "MCP_ENABLED", mcpEnabled.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
            "/META-INF/io.netty.versions.properties"
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.webkit)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}