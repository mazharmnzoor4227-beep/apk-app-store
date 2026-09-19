import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val storeBackendUrl = providers.gradleProperty("STORE_BACKEND_URL")
    .orElse("https://apk-app-store.mazharmnzoor4227.workers.dev")
    .get()
val generatedIconResDir = layout.buildDirectory.dir("generated/iconRes")
val generateStoreIcon = tasks.register("generateStoreIcon") {
    val source = layout.projectDirectory.file("icon-art.b64")
    inputs.file(source)
    outputs.dir(generatedIconResDir)
    doLast {
        val drawableDir = generatedIconResDir.get().asFile.resolve("drawable")
        drawableDir.mkdirs()
        val encoded = source.asFile.readText().trim()
        drawableDir.resolve("app_icon_art.webp").writeBytes(Base64.getDecoder().decode(encoded))
    }
}

android {
    namespace = "com.mazhar.apkappstore"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mazhar.apkappstore"
        minSdk = 29
        targetSdk = 35
        versionCode = 9
        versionName = "1.8.0"

        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"${storeBackendUrl.replace("\"", "\\\"")}\"")
    }

    sourceSets.getByName("main").res.srcDir(generatedIconResDir)

    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateStoreIcon)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
