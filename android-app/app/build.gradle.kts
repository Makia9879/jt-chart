import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

val releaseSigningPropertyNames = listOf(
    "JT_CHART_STORE_FILE",
    "JT_CHART_STORE_PASSWORD",
    "JT_CHART_KEY_ALIAS",
    "JT_CHART_KEY_PASSWORD",
)
val releaseRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("release", ignoreCase = true)
}
val releaseSigningProperties = releaseSigningPropertyNames.associateWith { name ->
    providers.gradleProperty(name).orNull?.takeIf(String::isNotBlank)
}
val missingReleaseSigningProperties =
    releaseSigningProperties.filterValues { it == null }.keys

if (releaseRequested) {
    if (missingReleaseSigningProperties.isNotEmpty()) {
        throw GradleException(
            "Release signing is required. Missing Gradle properties: " +
                missingReleaseSigningProperties.joinToString(),
        )
    }
}

gradle.taskGraph.whenReady {
    val graphContainsRelease = allTasks.any { task ->
        task.name.contains("release", ignoreCase = true)
    }
    if (graphContainsRelease && missingReleaseSigningProperties.isNotEmpty()) {
        throw GradleException(
            "Release signing is required. Missing Gradle properties: " +
                missingReleaseSigningProperties.joinToString(),
        )
    }
}

android {
    namespace = "com.makia.jtchart"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.makia.jtchart"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningProperties.values.all { it != null }) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningProperties["JT_CHART_STORE_FILE"]))
                storePassword = requireNotNull(releaseSigningProperties["JT_CHART_STORE_PASSWORD"])
                keyAlias = requireNotNull(releaseSigningProperties["JT_CHART_KEY_ALIAS"])
                keyPassword = requireNotNull(releaseSigningProperties["JT_CHART_KEY_PASSWORD"])
            }
        }
    }

    buildTypes {
        debug {}
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                id("java") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.protobuf.javalite)
    implementation(libs.webkit)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
