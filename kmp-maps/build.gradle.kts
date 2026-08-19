plugins { alias(libs.plugins.jetBrains.dokka) }

subprojects {
    // Fork coordinates: com.swmansion.* belongs to Software Mansion, so this fork publishes under
    // the fork owner's own verified Maven Central namespace. The version keeps the fork suffix so
    // it can never be mistaken for an upstream release. [DRIVE-PUCK-NATIVE-001]
    group = "io.github.rndevelo.kmpmaps"
    version = "0.9.1-puck4"
}

dependencies {
    dokka(project(":kmp-maps:core"))
    dokka(project(":kmp-maps:google-maps"))
}

dokka {
    moduleName = "KMP Maps"
    pluginsConfiguration.html {
        footerMessage =
            """
            © <a href="https://swmansion.com" rel="noopener noreferrer" target="_blank">Software Mansion</a> 2025.
            All trademarks and copyrights belong to their respective owners.
            """
                .trimIndent()
        customStyleSheets.from("$rootDir/logo-styles.css")
    }
}
