plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yemotai.videostudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yemotai.videostudio"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"
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
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}


// Copy the offline editor files from the source bundle into Android assets.
tasks.register<Copy>("syncEditorAssets") {
    from(rootProject.projectDir) {
        include("index.html", "app.css", "app.js", "manifest.webmanifest")
    }
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild") {
    dependsOn("syncEditorAssets")
}
