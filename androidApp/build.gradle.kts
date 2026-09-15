import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
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

private object SandboxNetworkSecurityPolicy {
    data class Endpoint(
        val baseUrl: String,
        val cleartextHosts: List<String>,
    )

    fun endpoint(value: String): Endpoint {
        val trimmedValue = value.trim()
        if (trimmedValue.isEmpty()) {
            return Endpoint(
                baseUrl = "",
                cleartextHosts = listOf("10.0.2.2", "127.0.0.1"),
            )
        }

        val uri = runCatching { URI(trimmedValue) }.getOrNull()
        val host = uri?.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
        require(
            uri != null &&
                uri.scheme?.lowercase() in setOf("http", "https") &&
                !host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
                uri.hasValidPort(),
        ) {
            "SANDBOX_BFF_BASE_URL must be an HTTP(S) origin without credentials, path, query, or fragment."
        }
        val normalizedValue = trimmedValue.trimEnd('/')

        if (uri.scheme.equals("https", ignoreCase = true)) {
            return Endpoint(baseUrl = normalizedValue, cleartextHosts = emptyList())
        }

        require(host.isPrivateSandboxHost()) {
            "SANDBOX_BFF_BASE_URL may use HTTP only for a loopback or supported private local-network host."
        }
        return Endpoint(baseUrl = normalizedValue, cleartextHosts = listOf(host))
    }

    fun render(cleartextHosts: List<String>): String {
        val domainConfig =
            cleartextHosts.joinToString(separator = "\n") { host ->
                "        <domain includeSubdomains=\"false\">$host</domain>"
            }.takeIf(String::isNotEmpty)?.let { domains ->
                """
                |    <domain-config cleartextTrafficPermitted="true">
                |$domains
                |    </domain-config>
                """.trimMargin()
            }
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            appendLine("<network-security-config>")
            appendLine("    <!-- Sandbox permits HTTP only to the exact build-selected local BFF host. -->")
            appendLine("    <base-config cleartextTrafficPermitted=\"false\" />")
            domainConfig?.let(::appendLine)
            appendLine("</network-security-config>")
        }
    }

    private fun URI.hasValidPort(): Boolean {
        val authority = rawAuthority ?: return false
        val hostPart = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else host ?: return false
        val portPart = authority.removePrefix(hostPart)
        return portPart.isEmpty() ||
            portPart.startsWith(':') && portPart.drop(1).toIntOrNull() in 1..65535
    }

    private fun String.isPrivateSandboxHost(): Boolean {
        if (this in setOf("10.0.2.2", "127.0.0.1", "::1", "localhost") || endsWith(".local")) return true
        if (':' in this && (startsWith("fc") || startsWith("fd"))) return true
        if (':' in this && take(3) in setOf("fe8", "fe9", "fea", "feb")) return true

        val octets = split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            octets[0] == 127 ||
            octets[0] == 169 && octets[1] == 254 ||
            octets[0] == 172 && octets[1] in 16..31 ||
            octets[0] == 192 && octets[1] == 168
    }
}

@CacheableTask
abstract class GenerateSandboxNetworkSecurityConfig : DefaultTask() {
    @get:Input
    abstract val cleartextHosts: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val outputFile = outputDirectory.file("xml/debug_network_security_config.xml").get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(SandboxNetworkSecurityPolicy.render(cleartextHosts.get()))
    }
}

@DisableCachingByDefault(because = "Fast policy assertions have no reusable output.")
abstract class VerifySandboxNetworkSecurityPolicy : DefaultTask() {
    @TaskAction
    fun verify() {
        fun assertEndpoint(
            value: String,
            expectedBaseUrl: String,
            expectedHosts: List<String>,
        ) {
            val actual = SandboxNetworkSecurityPolicy.endpoint(value)
            check(actual.baseUrl == expectedBaseUrl) { "Unexpected normalized Sandbox BFF origin for $value" }
            check(actual.cleartextHosts == expectedHosts) { "Unexpected cleartext allowlist for $value" }
            val xml = SandboxNetworkSecurityPolicy.render(actual.cleartextHosts)
            check("<base-config cleartextTrafficPermitted=\"false\" />" in xml)
            check("<base-config cleartextTrafficPermitted=\"true\"" !in xml)
            check(Regex("<domain includeSubdomains=\"false\">").findAll(xml).count() == expectedHosts.size)
            check(("<domain-config" in xml) == expectedHosts.isNotEmpty())
            expectedHosts.forEach { host -> check(">$host</domain>" in xml) }
        }

        assertEndpoint("", "", listOf("10.0.2.2", "127.0.0.1"))
        assertEndpoint("http://192.168.1.25:8080", "http://192.168.1.25:8080", listOf("192.168.1.25"))
        assertEndpoint("http://[fd00::1]:8080", "http://[fd00::1]:8080", listOf("fd00::1"))
        assertEndpoint("http://transit-bff.local:8080", "http://transit-bff.local:8080", listOf("transit-bff.local"))
        assertEndpoint("https://sandbox.example.test/", "https://sandbox.example.test", emptyList())

        listOf(
            "http://example.com:8080",
            "http://user:secret@192.168.1.25:8080",
            "http://192.168.1.25:8080/v1",
            "http://192.168.1.25:8080?mode=debug",
            "http://192.168.1.25:8080#fragment",
            "not a URL",
            "http://192.168.1.25:65536",
        ).forEach { rejected ->
            check(runCatching { SandboxNetworkSecurityPolicy.endpoint(rejected) }.isFailure) {
                "Unsafe Sandbox BFF origin was accepted: $rejected"
            }
        }
    }
}

@DisableCachingByDefault(because = "Fast assertions validate variant artifacts without producing an output.")
abstract class VerifyAndroidNetworkSecurityArtifacts : DefaultTask() {
    @get:Input
    abstract val expectedCleartextHosts: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedConfig: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sandboxPackagedResources: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val prodPackagedResources: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sandboxMergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val prodMergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val expectedConfig = SandboxNetworkSecurityPolicy.render(expectedCleartextHosts.get())
        check(generatedConfig.get().asFile.readText() == expectedConfig)
        check(
            sandboxPackagedResources.file("xml/debug_network_security_config.xml").get().asFile.readText() ==
                expectedConfig,
        )
        check(
            prodPackagedResources.get().asFile.walkTopDown().none { file ->
                file.name == "debug_network_security_config.xml"
            },
        ) { "Prod packaged the Sandbox-only network security resource." }

        val sandboxManifest = sandboxMergedManifest.get().asFile.readText()
        val prodManifest = prodMergedManifest.get().asFile.readText()
        check("android:networkSecurityConfig=\"@xml/debug_network_security_config\"" in sandboxManifest)
        check("networkSecurityConfig" !in prodManifest) { "Prod references a debug network security config." }
        check("android:usesCleartextTraffic=\"false\"" in prodManifest)
    }
}

private val sandboxEndpoint =
    SandboxNetworkSecurityPolicy.endpoint(
        providers.gradleProperty("SANDBOX_BFF_BASE_URL").orElse("").get(),
    )
val productionBffBaseUrl =
    requireProductionHttpsEndpoint("https://antik21-georgia-transit-bff.onrender.com")
private val generateSandboxNetworkSecurityConfig =
    tasks.register<GenerateSandboxNetworkSecurityConfig>("generateSandboxNetworkSecurityConfig") {
        cleartextHosts.set(sandboxEndpoint.cleartextHosts)
        outputDirectory.set(layout.buildDirectory.dir("generated/res/sandboxNetworkSecurityConfig"))
    }
private val verifySandboxNetworkSecurityPolicy =
    tasks.register<VerifySandboxNetworkSecurityPolicy>("verifySandboxNetworkSecurityPolicy") {
        group = "verification"
        description = "Verifies the Sandbox BFF origin allowlist and generated cleartext policy."
    }
generateSandboxNetworkSecurityConfig.configure { dependsOn(verifySandboxNetworkSecurityPolicy) }

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
            buildConfigField("String", "BFF_BASE_URL", buildConfigString(sandboxEndpoint.baseUrl))
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
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        val environment = variant.productFlavors.singleOrNull { it.first == "environment" }?.second
        variant.enable =
            (environment == "sandbox" && variant.buildType == "debug") ||
            (environment == "prod" && variant.buildType == "release")
    }
    onVariants(selector().all()) { variant ->
        if (variant.name == "sandboxDebug") {
            variant.sources.res?.addGeneratedSourceDirectory(
                generateSandboxNetworkSecurityConfig,
                GenerateSandboxNetworkSecurityConfig::outputDirectory,
            )
        }
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

private val verifyAndroidNetworkSecurityArtifacts =
    tasks.register<VerifyAndroidNetworkSecurityArtifacts>("verifyAndroidNetworkSecurityArtifacts") {
        group = "verification"
        description = "Verifies that only Sandbox packages the exact-host cleartext policy."
        dependsOn(
            generateSandboxNetworkSecurityConfig,
            "packageSandboxDebugResources",
            "packageProdReleaseResources",
            "processSandboxDebugMainManifest",
            "processProdReleaseMainManifest",
        )

        val generatedConfig =
            generateSandboxNetworkSecurityConfig.flatMap { task ->
                task.outputDirectory.file("xml/debug_network_security_config.xml")
            }
        val sandboxPackagedResources =
            layout.buildDirectory.dir("intermediates/packaged_res/sandboxDebug/packageSandboxDebugResources")
        val prodPackagedResources =
            layout.buildDirectory.dir("intermediates/packaged_res/prodRelease/packageProdReleaseResources")
        val sandboxMergedManifest =
            layout.buildDirectory.file(
                "intermediates/merged_manifest/sandboxDebug/processSandboxDebugMainManifest/AndroidManifest.xml",
            )
        val prodMergedManifest =
            layout.buildDirectory.file(
                "intermediates/merged_manifest/prodRelease/processProdReleaseMainManifest/AndroidManifest.xml",
            )
        expectedCleartextHosts.set(sandboxEndpoint.cleartextHosts)
        this.generatedConfig.set(generatedConfig)
        this.sandboxPackagedResources.set(sandboxPackagedResources)
        this.prodPackagedResources.set(prodPackagedResources)
        this.sandboxMergedManifest.set(sandboxMergedManifest)
        this.prodMergedManifest.set(prodMergedManifest)
    }

tasks.named("check").configure { dependsOn(verifyAndroidNetworkSecurityArtifacts) }
tasks.configureEach {
    if (name == "assembleProdRelease") dependsOn(verifyAndroidNetworkSecurityArtifacts)
}
