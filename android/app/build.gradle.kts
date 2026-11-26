import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
}

abstract class CopyPageLogicTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val src = sourceFile.get().asFile
        if (!src.exists()) {
            throw GradleException("Missing pageLogic.js at ${src.absolutePath}")
        }
        val outDir = outputDir.get().asFile
        outDir.mkdirs()
        src.copyTo(File(outDir, "pageLogic.js"), overwrite = true)
    }
}

val copyPageLogicTask = tasks.register<CopyPageLogicTask>("copyPageLogic") {
    sourceFile.set(rootProject.layout.projectDirectory.file("../extension/lib/pageLogic.js"))
    outputDir.set(layout.buildDirectory.dir("generated/assets/pageLogic"))
}

val releaseKeystoreFile = System.getenv("POLISWAG_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val releaseKeystorePassword = System.getenv("POLISWAG_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = System.getenv("POLISWAG_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = System.getenv("POLISWAG_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseSigning = releaseKeystoreFile != null &&
    releaseKeystorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "com.poliswag.passport"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.poliswag.passport"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        // Release CI passes the git tag (without the leading "v").
        versionName = System.getenv("POLISWAG_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.1.0"
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyPageLogicTask,
            CopyPageLogicTask::outputDir
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
