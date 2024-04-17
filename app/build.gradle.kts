plugins {
    id 'com.android.library'
    id 'kotlin-android'
    id 'maven-publish'
}

group = 'com.openreplay'
version = '1.0'

android {
    compileSdkVersion 31

    defaultConfig {
        minSdkVersion 21
        targetSdkVersion 31
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    kotlinOptions {
        jvmTarget = '1.8'
    }
}

dependencies {
    implementation "org.jetbrains.kotlin:kotlin-stdlib:$kotlin_version"
    implementation 'androidx.core:core-ktx:1.7.0'
    implementation 'androidx.appcompat:appcompat:1.4.1'
    implementation 'com.google.android.material:material:1.5.0'
}

afterEvaluate {
    publishing {
        publications {
            // Creates a Maven publication called "release".
            release(MavenPublication) {
                from components.release
                groupId = 'com.openreplay'
                artifactId = 'openreplay'
                version = '1.0'
            }
        }
    }
}