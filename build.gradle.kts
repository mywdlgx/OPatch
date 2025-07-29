import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.BaseExtension
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

plugins {
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.agp.app) apply false
    alias(lspatch.plugins.kotlin.android) apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.eclipse.jgit:org.eclipse.jgit:6.3.0.202209071007-r")
        classpath(kotlin("gradle-plugin", version = "1.8.21"))
    }
}

fun calculateCommitCount(): Int {
    return try {
        val repo = FileRepository(rootProject.file(".git"))
        val git = Git(repo)

        // Try different approaches to get commit count
        val refId = repo.refDatabase.exactRef("refs/remotes/origin/main")?.objectId
            ?: repo.refDatabase.exactRef("refs/remotes/origin/master")?.objectId
            ?: repo.refDatabase.exactRef("refs/heads/main")?.objectId
            ?: repo.refDatabase.exactRef("refs/heads/master")?.objectId
            ?: repo.refDatabase.exactRef("HEAD")?.objectId
            ?: repo.resolve("HEAD")

        if (refId != null) {
            git.log().add(refId).call().count()
        } else {
            // Fallback: try to get commit count from current branch
            git.log().call().count()
        }
    } catch (e: Exception) {
        println("Warning: Could not determine commit count from Git: ${e.message}")
        // Fallback to a default value
        1
    }
}

val commitCount = calculateCommitCount()

fun calculateCoreInfo(): Pair<Int, String> {
    return try {
        val coreGitDir = rootProject.file(".git/modules/core")
        if (coreGitDir.exists()) {
            val result = FileRepositoryBuilder().setGitDir(coreGitDir)
                .runCatching {
                    build().use { repo ->
                        val git = Git(repo)
                        val headRef = repo.refDatabase.exactRef("HEAD")?.objectId
                            ?: repo.resolve("HEAD")

                        val coreCommitCount = if (headRef != null) {
                            git.log().add(headRef).call().count() + 4200
                        } else {
                            git.log().call().count() + 4200
                        }

                        val ver = try {
                            git.describe().setTags(true).setAbbrev(0).call().removePrefix("v")
                        } catch (e: Exception) {
                            "1.0"
                        }
                        coreCommitCount to ver
                    }
                }.getOrNull()
            result ?: (4201 to "1.0")
        } else {
            println("Warning: Core submodule not found, using default values")
            (4201 to "1.0")
        }
    } catch (e: Exception) {
        println("Warning: Could not determine core module info: ${e.message}")
        (4201 to "1.0")
    }
}

val coreInfoResult = calculateCoreInfo()
val coreCommitCount = coreInfoResult.first
val coreLatestTag = coreInfoResult.second

// sync from https://github.com/LSPosed/LSPosed/blob/master/build.gradle.kts
val defaultManagerPackageName by extra("org.lsposed.opatch")
val apiCode by extra(93)
val verCode by extra(7)
val verName by extra("0.0.7")
val coreVerCode by extra(coreCommitCount)
val coreVerName by extra(coreLatestTag)
val androidMinSdkVersion by extra(28)
val androidTargetSdkVersion by extra(34)
val androidCompileSdkVersion by extra(34)
val androidCompileNdkVersion by extra("25.2.9519653")
val androidBuildToolsVersion by extra("34.0.0")
val androidSourceCompatibility by extra(JavaVersion.VERSION_17)
val androidTargetCompatibility by extra(JavaVersion.VERSION_17)

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}

listOf("Debug", "Release").forEach { variant ->
    tasks.register("build$variant") {
        description = "Build LSPatch with $variant"
        dependsOn(projects.jar.dependencyProject.tasks["build$variant"])
        dependsOn(projects.manager.dependencyProject.tasks["build$variant"])
    }
}

tasks.register("buildAll") {
    dependsOn("buildDebug", "buildRelease")
}

fun Project.configureBaseExtension() {
    extensions.findByType(BaseExtension::class)?.run {
        compileSdkVersion(androidCompileSdkVersion)
        ndkVersion = androidCompileNdkVersion
        buildToolsVersion = androidBuildToolsVersion

        externalNativeBuild.cmake {
            version = "3.22.1+"
        }

        defaultConfig {
            minSdk = androidMinSdkVersion
            targetSdk = androidTargetSdkVersion
            versionCode = verCode
            versionName = verName

            signingConfigs.create("config") {
                val androidStoreFile = project.findProperty("androidStoreFile") as String?
                if (!androidStoreFile.isNullOrEmpty()) {
                    storeFile = rootProject.file(androidStoreFile)
                    storePassword = project.property("androidStorePassword") as String
                    keyAlias = project.property("androidKeyAlias") as String
                    keyPassword = project.property("androidKeyPassword") as String
                }
            }

            externalNativeBuild {
                cmake {
                    arguments += "-DEXTERNAL_ROOT=${File(rootDir.absolutePath, "core/external")}"
                    arguments += "-DCORE_ROOT=${File(rootDir.absolutePath, "core/core/src/main/jni")}"
                    abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                    val flags = arrayOf(
                        "-Wall",
                        "-Qunused-arguments",
                        "-Wno-gnu-string-literal-operator-template",
                        "-fno-rtti",
                        "-fvisibility=hidden",
                        "-fvisibility-inlines-hidden",
                        "-fno-exceptions",
                        "-fno-stack-protector",
                        "-fomit-frame-pointer",
                        "-Wno-builtin-macro-redefined",
                        "-Wno-unused-value",
                        "-D__FILE__=__FILE_NAME__",
                    )
                    cppFlags("-std=c++20", *flags)
                    cFlags("-std=c18", *flags)
                    arguments(
                        "-DANDROID_STL=none",
                        "-DVERSION_CODE=$verCode",
                        "-DVERSION_NAME=$verName",
                    )
                }
            }
        }

        compileOptions {
            targetCompatibility(androidTargetCompatibility)
            sourceCompatibility(androidSourceCompatibility)
        }

        buildTypes {
            all {
                signingConfig = if (signingConfigs["config"].storeFile != null) signingConfigs["config"] else signingConfigs["debug"]
            }
            named("debug") {
                externalNativeBuild {
                    cmake {
                        arguments.addAll(
                            arrayOf(
                                "-DCMAKE_CXX_FLAGS_DEBUG=-Og",
                                "-DCMAKE_C_FLAGS_DEBUG=-Og",
                            )
                        )
                    }
                }
            }
            named("release") {
                externalNativeBuild {
                    cmake {
                        val flags = arrayOf(
                            "-Wl,--exclude-libs,ALL",
                            "-ffunction-sections",
                            "-fdata-sections",
                            "-Wl,--gc-sections",
                            "-fno-unwind-tables",
                            "-fno-asynchronous-unwind-tables",
                            "-flto=thin",
                            "-Wl,--thinlto-cache-policy,cache_size_bytes=300m",
                            "-Wl,--thinlto-cache-dir=${buildDir.absolutePath}/.lto-cache",
                        )
                        cppFlags.addAll(flags)
                        cFlags.addAll(flags)
                        val configFlags = arrayOf(
                            "-Oz",
                            "-DNDEBUG"
                        ).joinToString(" ")
                        arguments.addAll(
                            arrayOf(
                                "-DCMAKE_CXX_FLAGS_RELEASE=$configFlags",
                                "-DCMAKE_CXX_FLAGS_RELWITHDEBINFO=$configFlags",
                                "-DCMAKE_C_FLAGS_RELEASE=$configFlags",
                                "-DCMAKE_C_FLAGS_RELWITHDEBINFO=$configFlags",
                                "-DDEBUG_SYMBOLS_PATH=${buildDir.absolutePath}/symbols",
                            )
                        )
                    }
                }
            }
        }
    }

    extensions.findByType(ApplicationExtension::class)?.lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    extensions.findByType(ApplicationAndroidComponentsExtension::class)?.let { androidComponents ->
        val optimizeReleaseRes = task("optimizeReleaseRes").doLast {
            val aapt2 = File(
                androidComponents.sdkComponents.sdkDirectory.get().asFile,
                "build-tools/${androidBuildToolsVersion}/aapt2"
            )
            val zip = java.nio.file.Paths.get(
                project.buildDir.path,
                "intermediates",
                "optimized_processed_res",
                "release",
                "resources-release-optimize.ap_"
            )
            val optimized = File("${zip}.opt")
            val cmd = exec {
                commandLine(
                    aapt2, "optimize",
                    "--collapse-resource-names",
                    "--enable-sparse-encoding",
                    "-o", optimized,
                    zip
                )
                isIgnoreExitValue = false
            }
            if (cmd.exitValue == 0) {
                delete(zip)
                optimized.renameTo(zip.toFile())
            }
        }

        tasks.configureEach {
            if (name == "optimizeReleaseResources") {
                finalizedBy(optimizeReleaseRes)
            }
        }
    }
}

subprojects {
    plugins.withId("com.android.application") {
        configureBaseExtension()
    }
    plugins.withId("com.android.library") {
        configureBaseExtension()
    }
}
