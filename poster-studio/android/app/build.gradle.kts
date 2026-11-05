plugins {
    id("com.android.application")
}
android {
    namespace = "com.yemotai.posterstudio"
    compileSdk = 35
    defaultConfig {
        // A separate install ID prevents Android from rejecting this rebuilt APK
        // when an older copy was signed with a different debug certificate.
        applicationId = "com.yemotai.posterstudio.repaired"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.register<Copy>("syncStudioAssets") {
    from("../../index.html")
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild") { dependsOn("syncStudioAssets") }
