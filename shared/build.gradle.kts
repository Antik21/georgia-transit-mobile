import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.Architecture
import org.jetbrains.kotlin.konan.target.HostManager
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    rootProject.the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download.set(false)
}
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
    project.the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download.set(false)
}
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    rootProject.the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec>().download.set(false)
}

// Android Studio resolves previews from this KMP module's Android runtime classpath.
// This is not packaged into the consuming application's runtime variants.
dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        events(TestLogEvent.FAILED)
    }
}

kotlin {
    android {
        namespace = "com.denis.georgiatransit.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}.configure {}
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        androidResources { enable = true }
    }

    js {
        browser {
            testTask {
                useKarma { useChromeHeadless() }
            }
        }
        binaries.library()
        // Compose's browser test setup needs webpack to load the transitive Skiko runtime.
        binaries.executable()
        generateTypeScriptDefinitions()
    }

    iosArm64()
    iosSimulatorArm64()
    if (HostManager.hostIsMac && HostManager.host.architecture == Architecture.X64) {
        iosX64()
    }

    applyDefaultHierarchyTemplate()

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.resources)
            implementation(libs.koin.core)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.orbit.core)
            implementation(libs.orbit.viewmodel)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        val mobileMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)
                implementation(libs.jetbrains.navigation3.ui)
                implementation(libs.jetbrains.lifecycle.runtime.compose)
                implementation(libs.jetbrains.lifecycle.viewmodel.compose)
                implementation(libs.jetbrains.lifecycle.viewmodel.navigation3)
                implementation(libs.orbit.compose)
            }
        }
        androidMain.get().dependsOn(mobileMain)
        iosMain.get().dependsOn(mobileMain)
        val mobileTest by creating { dependsOn(commonTest.get()) }
        named("androidHostTest") { dependsOn(mobileTest) }
        iosTest.get().dependsOn(mobileTest)
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.maplibre.android)
        }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        jsMain.dependencies {
            implementation(libs.ktor.client.js)
            implementation(libs.kotlin.dom.api.compat)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.orbit.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

compose.resources {
    packageOfResClass = "georgiatransit.shared.generated.resources"
}
