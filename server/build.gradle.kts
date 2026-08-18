plugins {
    id("com.android.library")
}

android {
    namespace = "com.vdcontroller.server"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Build a jar that can be run via app_process
    libraryVariants.all {
        val variant = this
        variant.outputs.all {
            val taskName = "jar${variant.name.replaceFirstChar { it.uppercase() }}"
            tasks.register<Jar>(taskName) {
                dependsOn(variant.javaCompileProvider)
                from(variant.javaCompileProvider.get().destinationDirectory)
                archiveFileName.set("vdserver.jar")
                destinationDirectory.set(file("${project.buildDir}/libs"))
            }
        }
    }
}

dependencies {
    // Compile against the Android framework only; no AndroidX needed for server
    compileOnly(files("${android.sdkDirectory}/platforms/android-34/android.jar"))
}
