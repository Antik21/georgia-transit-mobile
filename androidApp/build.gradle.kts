import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

private fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

private fun requireProductionHttpsEndpoint(value: String): String {
    val uri = runCatching { URI(value) }.getOrNull()
    require(
        uri != null &&
            uri.scheme == "https" &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.query == null &&
            uri.fragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/"),
    ) {
        "The production BFF URL must be an HTTPS origin without credentials, path, query, or fragment."
    }
    return value.trimEnd('/')
}

val sandboxBffBaseUrl = providers.gradleProperty("SANDBOX_BFF_BASE_URL").orElse("").get()
val productionBffBaseUrl =
    requireProductionHttpsEndpoint("https://antik21-georgia-transit-bff.onrender.com")

android {
    namespace = "com.denis.georgiatransit.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.denis.georgiatransit"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "environment"
    productFlavors {
        create("sandbox") {
            dimension = "environment"
            applicationIdSuffix = ".sandbox"
            versionNameSuffix = "-sandbox"
            resValue("string", "app_name", "Georgia Transit Sandbox")
            buildConfigField("String", "BFF_BASE_URL", buildConfigString(sandboxBffBaseUrl))
        }
        create("prod") {
            dimension = "environment"
            resValue("string", "app_name", "Georgia Transit")
            buildConfigField("String", "BFF_BASE_URL", buildConfigString(productionBffBaseUrl))
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        val environment = variant.productFlavors.singleOrNull { it.first == "environment" }?.second
        variant.enable =
            (environment == "sandbox" && variant.buildType == "debug") ||
            (environment == "prod" && variant.buildType == "release")
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
    debugImplementation(libs.chucker)
    debugImplementation(libs.ktor.client.core)
}
