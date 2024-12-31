plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    id("com.gradleup.nmcp").version("0.0.8")
}

nmcp {
    publishAggregation {
        project(":tunnel")
        username = getLocalProperty("MAVEN_CENTRAL_USER")
        password = getLocalProperty("MAVEN_CENTRAL_PASS")
        publicationType = "AUTOMATIC"
    }
}