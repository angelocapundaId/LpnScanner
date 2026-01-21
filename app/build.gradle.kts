plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.empresa.lpnscanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.empresa.lpnscanner"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ===== Assinatura =====
    signingConfigs {
        create("release") {
            // Se quiser gerar seu próprio .jks depois, troque esse caminho e senhas
            storeFile = file("C:/Users/Lucas/.android/debug.keystore")
            storePassword = "InfraIDSacanner!"
            keyAlias = "lpnscanner"
            keyPassword = "InfraIDSacanner!"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        dataBinding = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    // Android UI
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // CameraX
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // ZXing (para leitura de código de barras)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // ML Kit (apenas uma versão)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Firebase (BOM controla as versões)
    implementation(platform("com.google.firebase:firebase-bom:33.3.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth") // ✅ Adicionado para corrigir o erro do FirebaseAuth

    // Testes
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
