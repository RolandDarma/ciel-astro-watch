plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.darmagnac.cielastro"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.darmagnac.cielastro"
        minSdk = 30          // Wear OS 3 et au-delà (la Galaxy Watch 4 est en Wear OS 6)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Tuile Wear OS
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-expression:1.2.1")
    // ListenableFuture sans tirer tout Guava
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
}
