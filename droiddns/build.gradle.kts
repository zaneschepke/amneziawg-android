plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    signing
//    id("com.gradleup.nmcp").version("0.0.8")
}

android {
    namespace = "com.zaneschepke.droiddns"
    compileSdk = 34

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    publishing {
        singleVariant("release") {
            withJavadocJar()
            withSourcesJar()
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    //dns
    implementation(libs.dnsjava)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.zaneschepke"
            artifactId = "droiddns"
            version = "1.1.0"
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name.set("Android DNS Resolution Library")
                description.set("A simple DNS resolution library for Android.")
                url.set("https://amnezia.org/")

                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/zaneschepke/amneziawg-android")
                    developerConnection.set("scm:git:https://github.com/zaneschepke/amneziawg-android")
                    url.set("https://github.com/zaneschepke/amneziawg-android")
                }
                developers {
                    organization {
                        name.set("Zane Schepke")
                        url.set("https://zaneschepke.com")
                    }
                    developer {
                        name.set("Zane Schepke")
                        email.set("support@zaneschepke.com")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "sonatype"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = getLocalProperty("MAVEN_CENTRAL_USER")
                password = getLocalProperty("MAVEN_CENTRAL_PASS")
            }
        }
    }
}


//nmcp {
//    publishAllPublications {
//        username = getLocalProperty("MAVEN_CENTRAL_USER")
//        password = getLocalProperty("MAVEN_CENTRAL_PASS")
//    }
//}

signing {
    sign(publishing.publications)
}