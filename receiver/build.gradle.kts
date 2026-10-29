plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.audiobridge.receiver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.audiobridge.receiver"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Turkish is the default UI language; English is included as a translated locale.
        androidResources.localeFilters += listOf("tr", "en")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "$rootDir/shared/src/main/java")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
