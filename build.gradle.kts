plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.spotless)
}

spotless {
    format("kotlin") {
        target("**/*.kt")
        trimTrailingWhitespace()
    }
    format("kotlinGradle") {
        target("*.gradle.kts", "**/*.gradle.kts")
        trimTrailingWhitespace()
    }
    format("misc") {
        target(
            ".gitattributes",
            ".gitignore",
            ".github/**/*.yml",
            ".github/**/*.yaml",
            "docs/**/*.yaml",
            "README.md",
            "transitBff/.env.example",
            "transitBff/src/main/resources/**/*.env",
            "transitBff/src/main/resources/**/*.yml",
        )
        targetExclude(
            "**/.gradle/**",
            "**/build/**",
            "**/DerivedData/**",
            "iosApp/iosApp.xcodeproj/project.pbxproj",
            "**/Package.resolved",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}
