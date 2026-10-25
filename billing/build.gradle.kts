plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

val sdkGroup = providers.gradleProperty("BILLING_SDK_GROUP").get()
val sdkArtifact = providers.gradleProperty("BILLING_SDK_ARTIFACT").get()
val sdkVersion = providers.gradleProperty("billingSdkVersion").orElse(libs.versions.billing.sdk.get()).get()

android {
    namespace = "com.zyroxlimited.billing"
    // 36 rather than 37 so JitPack's build image is sure to have the platform.
    //noinspection GradleDependency
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // Lowest compileSdk an app needs to use the SDK (lifecycle-process 2.11 requires 34). Without this, AGP
        // publishes the SDK's own compileSdk and forces every consuming app onto it.
        aarMetadata {
            minCompileSdk = 34
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    // Every public declaration must state its visibility: nothing leaks into the SDK surface by accident.
    explicitApi()
}

dependencies {
    // Exposed in the public API (StateFlow / SharedFlow), so consumers get it on their compile classpath.
    api(libs.kotlinx.coroutines.android)

    // Internal implementation details; Play types never appear in the public API.
    implementation(libs.play.billing)
    implementation(libs.androidx.lifecycle.process)
}

// Distributed through JitPack (see jitpack.yml), which runs publishToMavenLocal.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = sdkGroup
            artifactId = sdkArtifact
            version = sdkVersion

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("Google IAP Library")
                description.set(
                    "Google Play Billing wrapper: product catalog, purchase flow, subscription replacement, " +
                        "acknowledgement and a single premium entitlement StateFlow.",
                )
                url.set("https://github.com/Awaiz-hassan/Google-IAP-Library")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                scm {
                    url.set("https://github.com/Awaiz-hassan/Google-IAP-Library")
                    connection.set("scm:git:https://github.com/Awaiz-hassan/Google-IAP-Library.git")
                }
            }
        }
    }
}
