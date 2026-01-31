plugins {
    id("maven-publish")
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

android {
    namespace = "com.haishinkit.unity"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(
                org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17,
            )
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = rootProject.ext["PUBLISH_GROUP_ID"] as? String
                artifactId = "unity"
                version = rootProject.ext["PUBLISH_VERSION"] as? String
            }
        }
    }
}

dependencies {
    api(project(":haishinkit"))
    api(project(":rtmp"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
}
