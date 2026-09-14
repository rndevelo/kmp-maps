plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetBrains.compose)
    alias(libs.plugins.jetBrains.dokka)
    alias(libs.plugins.jetBrains.kotlin.multiplatform)
    alias(libs.plugins.jetBrains.kotlin.plugin.compose)
    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.jetBrains.kotlin.plugin.serialization)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
    androidTarget { publishLibraryVariants("release") }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "core"
            isStatic = true
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.components.resources)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(libs.jetBrains.androidX.lifecycle.runtimeCompose)
            implementation(libs.jetBrains.androidX.lifecycle.viewmodelCompose)
            implementation(compose.materialIconsExtended)
            implementation(libs.jetBrains.kotlinX.serialization.json)
        }

        androidMain.dependencies {
            implementation(libs.google.accompanist.permissions)
            implementation(libs.google.android.gms.playServicesMaps)
            implementation(libs.google.maps.android.mapsCompose)
            implementation(libs.google.maps.android.mapsComposeUtils)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.jetBrains.kotlinX.coroutinesSwing)
            implementation(libs.kevinnZou.composeWebViewMultiplatformDesktop)
        }
    }
}

android {
    namespace = "com.swmansion.kmpmaps.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dokka { dokkaPublications.configureEach { suppressInheritedMembers = true } }

mavenPublishing {
    // Explicit: uploading only creates a pending deployment in the Central Portal. Releasing it
    // to Maven Central is permanent and stays a deliberate manual step.
    publishToMavenCentral(automaticRelease = false)
    signAllPublications()
    pom {
        name = "KMP Maps (rndevelo fork)"
        description =
            "Unofficial fork of software-mansion/kmp-maps: a universal map component for Compose " +
                "Multiplatform. Adds stable marker ids, native marker rotation and LiveMarker, so " +
                "a moving marker glides instead of being destroyed and recreated on every " +
                "position change. Offered upstream as PR #170; this fork exists only until that " +
                "lands in an official release."
        url = "https://github.com/rndevelo/kmp-maps"
        inceptionYear = "2025"
        licenses {
            license {
                name = "The MIT License"
                url = "http://www.opensource.org/licenses/mit-license.php"
            }
        }
        scm {
            connection = "scm:git:git://github.com/rndevelo/kmp-maps.git"
            developerConnection = "scm:git:ssh://github.com/rndevelo/kmp-maps.git"
            url = "https://github.com/rndevelo/kmp-maps"
        }
        developers {
            // Fork maintainer: responsible for this artifact only, not for upstream.
            developer {
                id = "rndevelo"
                name = "rndevelo"
                url = "https://github.com/rndevelo"
                roles = listOf("Fork maintainer")
            }
            // Original authors of KMP Maps at Software Mansion. Listed for attribution under the
            // MIT license; they neither publish nor endorse this fork.
            developer {
                id = "arturgesiarz"
                name = "Artur Gęsiarz"
                email = "artur.gesiarz@swmansion.com"
                organization = "Software Mansion"
                organizationUrl = "https://swmansion.com"
                roles = listOf("Original author")
            }
            developer {
                id = "marekkaput"
                name = "Marek Kaput"
                email = "marek.kaput@swmansion.com"
                organization = "Software Mansion"
                organizationUrl = "https://swmansion.com"
                roles = listOf("Original author")
            }
            developer {
                id = "patrickmichalik"
                name = "Patrick Michalik"
                email = "patrick.michalik@swmansion.com"
                organization = "Software Mansion"
                organizationUrl = "https://swmansion.com"
                roles = listOf("Original author")
            }
            developer {
                id = "justynagreda"
                name = "Justyna Gręda"
                email = "justyna.greda@swmansion.com"
                organization = "Software Mansion"
                organizationUrl = "https://swmansion.com"
                roles = listOf("Original author")
            }
        }
    }
}
