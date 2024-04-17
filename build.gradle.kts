buildscript {
    ext.kotlin_version = "1.6.10"
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:4.2.2'
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
    }
}

allprojects {
    repositories {
        google()
        maven { url "https://jitpack.io" }
        mavenCentral()
    }
}

task clean(type: Delete) {
    delete rootProject.buildDir
}