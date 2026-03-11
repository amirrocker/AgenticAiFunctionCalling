// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

//allprojects {
//    repositories {
//        mavenCentral()
//        maven { url = uri("https://repo.maven.apache.org/maven2") }
//        maven { url = uri("https://maven.google.com") }
//        google()
//    }
//}
