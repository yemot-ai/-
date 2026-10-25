plugins {
    id("com.android.application")
}
android {
    namespace = "com.yemotai.posterstudio"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.yemotai.posterstudio"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
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
