plugins {
   alias(libs.plugins.android.application)
   alias(libs.plugins.kotlin.android)
   alias(libs.plugins.kotlin.compose)
   alias(libs.plugins.google.devtools.ksp)
   alias(libs.plugins.roborazzi)
   alias(libs.plugins.secrets)
   alias(libs.plugins.google.services)
   id("kotlin-parcelize")
 }
 
 android {
   namespace = "com.example.gusa"
   compileSdk = 35
 
   defaultConfig {
     applicationId = "com.example.gusa"
     minSdk = 24
     targetSdk = 35
     multiDexEnabled = true
     versionCode = 1
     versionName = "1.0"
 
     testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
     // Do not hardcode API keys in source. Read MAPS_API_KEY from a project property or environment variable.
     val mapsApiKey = project.findProperty("MAPS_API_KEY")?.toString() ?: System.getenv("MAPS_API_KEY") ?: ""
     manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
 
     val geminiKey = project.findProperty("GEMINI_API_KEY") ?: System.getenv("GEMINI_API_KEY") ?: ""
     buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
   }
 
   signingConfigs {
     create("release") {
       val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
       storeFile = file(keystorePath)
       storePassword = System.getenv("STORE_PASSWORD")
       keyAlias = "upload"
       keyPassword = System.getenv("KEY_PASSWORD")
     }
   }
 
   buildTypes {
     release {
       isCrunchPngs = false
       isMinifyEnabled = false
       proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
       signingConfig = signingConfigs.getByName("release")
     }
     debug {
     }
   }
   compileOptions {
     sourceCompatibility = JavaVersion.VERSION_11
     targetCompatibility = JavaVersion.VERSION_11
     isCoreLibraryDesugaringEnabled = true
   }
   kotlin {
     compilerOptions {
         jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
     }
   }
   buildFeatures {
     viewBinding = true
     buildConfig = true
   }
   packaging {
     jniLibs {
       useLegacyPackaging = true
     }
   }
   testOptions { unitTests { isIncludeAndroidResources = true } }
 
 
 }
 
 
 dependencies {
   coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
   implementation(libs.androidx.core.ktx)
   implementation(libs.androidx.appcompat)
   implementation(libs.androidx.swiperefreshlayout)
   implementation("com.google.android.material:material:1.14.0")
   
   implementation(platform(libs.androidx.compose.bom))
   implementation(libs.androidx.compose.ui)
   implementation(libs.androidx.compose.ui.graphics)
   implementation(libs.androidx.compose.ui.tooling.preview)
   implementation(libs.androidx.compose.material3)
   implementation(libs.androidx.compose.material.icons.core)
   implementation(libs.androidx.compose.material.icons.extended)
   
   implementation(libs.androidx.activity.compose)
   implementation(libs.androidx.lifecycle.runtime.ktx)
   implementation(libs.androidx.lifecycle.runtime.compose)
   implementation(libs.androidx.lifecycle.viewmodel.compose)
 
   implementation(platform(libs.firebase.bom))
   implementation(libs.firebase.auth)
   implementation(libs.firebase.firestore)
   implementation(libs.firebase.database)
   implementation(libs.firebase.appcheck.playintegrity)
   implementation(libs.firebase.appcheck.debug)
   implementation(libs.firebase.ai)
 
   implementation(libs.play.services.location)
   implementation(libs.play.services.cronet) {
     exclude(group = "org.chromium.net", module = "cronet-fallback")
   }
   implementation(libs.playServicesBase)
   implementation(libs.playServicesBasement)
   
   // Navigation SDK
   implementation(libs.google.navigation) {
     exclude(group = "org.chromium.net", module = "cronet-fallback")
     exclude(group = "org.chromium.net", module = "cronet-common")
   }
 
   implementation(libs.androidx.room.runtime)
   implementation(libs.androidx.room.ktx)
   implementation(libs.itext7.core)
   implementation(libs.mpandroidchart)
   "ksp"(libs.androidx.room.compiler)
 
   implementation(libs.retrofit)
   implementation(libs.converter.moshi)
   implementation(libs.moshi.kotlin)
   "ksp"(libs.moshi.kotlin.codegen)
   implementation(libs.okhttp)
   implementation(libs.logging.interceptor)
 
   implementation(libs.kotlinx.coroutines.core)
   implementation(libs.kotlinx.coroutines.android)
 
   testImplementation(libs.junit)
   testImplementation(libs.androidx.junit)
   testImplementation(libs.androidx.core)
   testImplementation(libs.kotlinx.coroutines.test)
   testImplementation(libs.robolectric)
   testImplementation(libs.roborazzi)
   testImplementation(libs.roborazzi.compose)
   testImplementation(libs.roborazzi.junit.rule)
 
   androidTestImplementation(platform(libs.androidx.compose.bom))
   androidTestImplementation(libs.androidx.compose.ui.test.junit4)
   androidTestImplementation(libs.androidx.espresso.core)
   androidTestImplementation(libs.androidx.junit)
   androidTestImplementation(libs.androidx.runner)
 
   debugImplementation(libs.androidx.compose.ui.tooling)
   debugImplementation(libs.androidx.compose.ui.test.manifest)
 }
