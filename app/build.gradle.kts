plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.map"
    compileSdk = 34
    val releaseKeystoreFile = System.getenv("MAP_KEYSTORE_FILE")
    val releaseKeystorePassword = System.getenv("MAP_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("MAP_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("MAP_KEY_PASSWORD")
    val releaseKeystoreType = System.getenv("MAP_KEYSTORE_TYPE") ?: "PKCS12"
    val hasExternalReleaseSigning = !releaseKeystoreFile.isNullOrBlank()
        && !releaseKeystorePassword.isNullOrBlank()
        && !releaseKeyAlias.isNullOrBlank()
        && !releaseKeyPassword.isNullOrBlank()

    defaultConfig {
        applicationId = "com.map"
        minSdk = 21
        targetSdk = 34
        versionCode = 18
        versionName = "0.2.1"
        buildConfigField(
            "String",
            "UPDATE_METADATA_URL",
            "\"https://github.com/kalkalch/map/releases/latest/download/update.json\""
        )
        buildConfigField(
            "String",
            "UPDATE_SIGNING_PUBLIC_KEY_DER_BASE64",
            "\"MCowBQYDK2VwAyEACHguub2zPii+TZNkzozv2hBMDjnEfh6pPQRHuoLK5n0=\""
        )
    }

    signingConfigs {
        if (hasExternalReleaseSigning) {
            create("externalRelease") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeType = releaseKeystoreType
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasExternalReleaseSigning) {
                signingConfigs.getByName("externalRelease")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
}
