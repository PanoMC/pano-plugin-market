import java.net.URL

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("kapt") version "2.2.21"
    id("com.gradleup.shadow") version "8.3.8"
    `maven-publish`
}

group = "com.panomc.plugins"
version =
    (if (project.hasProperty("version") && project.findProperty("version") != "unspecified") project.findProperty("version") else "local-build")!!

val pf4jVersion: String by project
val vertxVersion: String by project
val gsonVersion: String by project
val handlebarsVersion: String by project
val springContextVersion: String by project
val bootstrap = (project.findProperty("bootstrap") as String?)?.toBoolean() ?: false
val noui = project.hasProperty("noui")
val pluginsDir: File? by rootProject.extra

val os = System.getProperty("os.name").lowercase()
val arch = System.getProperty("os.arch").lowercase()

val isWindows = os.contains("win")
val isMac = os.contains("mac")
val isLinux = os.contains("nix") || os.contains("nux") || os.contains("linux")

val isAarch64 = arch.contains("aarch64") || arch.contains("arm64")
val isX64 = arch.contains("x86_64") || arch.contains("amd64")

val bunVersion = "1.2.0"
val bunPlatform = when {
    isWindows && isX64 -> "bun-windows-x64"
//    isWindows && isAarch64 -> "bun-windows-aarch64"
    isMac && isX64 -> "bun-darwin-x64"
    isMac && isAarch64 -> "bun-darwin-aarch64"
    isLinux && isX64 -> "bun-linux-x64"
    isLinux && isAarch64 -> "bun-linux-aarch64"
    else -> throw RuntimeException("Unsupported OS or Architecture")
}
val bunUrl = "https://github.com/oven-sh/bun/releases/download/bun-v$bunVersion/$bunPlatform.zip"

val bunDir = File(layout.buildDirectory.asFile.get().absolutePath, "bun")
val bunBinDir = File(bunDir, bunPlatform)
var bunBin = if (isWindows) File(bunBinDir, "bun.exe") else File(bunBinDir, "bun")

val pluginId: String by project
val pluginName: String by project
val pluginDescription: String? by project
val pluginPanoVersion: String by project
val pluginClass: String by project
val pluginDeveloper: String by project
val pluginLicense: String? by project
val pluginSourceUrl: String? by project
val pluginDependencies: String? by project
val pluginRequires: String? by project

val organization: String? by project

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    if (bootstrap) {
        compileOnly(project(mapOf("path" to ":Pano")))
    } else {
        compileOnly("com.github.panomc:pano:v1.0.0-alpha.492")
    }

    compileOnly(kotlin("stdlib-jdk8"))
    compileOnly(kotlin("reflect"))

    compileOnly("org.pf4j:pf4j:${pf4jVersion}")
    kapt("org.pf4j:pf4j:${pf4jVersion}")
    compileOnly("io.vertx:vertx-web:${vertxVersion}")
    compileOnly("io.vertx:vertx-lang-kotlin:${vertxVersion}")
    compileOnly("io.vertx:vertx-lang-kotlin-coroutines:${vertxVersion}")
    compileOnly("io.vertx:vertx-jdbc-client:${vertxVersion}")
    compileOnly("io.vertx:vertx-json-schema:${vertxVersion}")
    compileOnly("io.vertx:vertx-web-validation:${vertxVersion}")
    compileOnly("io.vertx:vertx-mysql-client:${vertxVersion}")

    // https://mvnrepository.com/artifact/org.springframework/spring-context
    compileOnly("org.springframework:spring-context:${springContextVersion}")

    // Same JUnit setup as the host (see Pano/build.gradle.kts).
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.3")
    // Gradle 9 requires the JUnit Platform launcher on the test runtime classpath explicitly.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.3")
    testImplementation("io.vertx:vertx-unit:${vertxVersion}")
    testImplementation("io.vertx:vertx-lang-kotlin-coroutines:${vertxVersion}")
}

tasks {
    register("installBun") {
        doLast {
            if (!bunBin.exists()) {
                println("🚀 Couldn't find Bun, downloading: $bunUrl")

                val zipFile = File(bunDir, "$bunPlatform.zip")
                zipFile.parentFile.mkdirs()

                URL(bunUrl).openStream().use { input ->
                    zipFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                copy {
                    from(zipTree(zipFile))
                    into(bunDir)
                }

                if (!isWindows) {
                    bunBin.setExecutable(true)
                }

                zipFile.delete()
                println("✅ Bun successfully downloaded: ${bunBin.absolutePath}")
            } else {
                println("✅ Bun is downloaded already: ${bunBin.absolutePath}")
            }
        }
    }

    register("buildUI", Exec::class) {
        dependsOn("installBun")
        println(bunBin.absolutePath)
        commandLine(bunBin.absolutePath, "run", "build")
    }

    register("zipPluginUI", Zip::class) {
        dependsOn("buildUI")

        from("src/main/resources/plugin-ui")
        archiveFileName.set("plugin-ui.zip")
        destinationDirectory.set(file("src/main/resources"))

        doLast {
            val pluginUIFolder = file("src/main/resources/plugin-ui")
            if (pluginUIFolder.exists()) {
                pluginUIFolder.deleteRecursively()
            }
        }

        outputs.upToDateWhen { false }
    }

    shadowJar {
        manifest {
            attributes["id"] = pluginId
            attributes["name"] = pluginName
            pluginDescription?.let { attributes["description"] = it }
            attributes["pano-version"] = pluginPanoVersion
            attributes["main-class"] = pluginClass
            attributes["version"] = version
            attributes["developer"] = pluginDeveloper
            pluginLicense?.let { attributes["license"] = it }
            pluginSourceUrl?.let { attributes["source-url"] = it }
            pluginDependencies?.let { attributes["dependencies"] = it }
            pluginRequires?.let { attributes["requires"] = it }
        }

        archiveFileName.set("$pluginId-$version.jar")

        dependencies {
            exclude(dependency("io.vertx:vertx-core"))
            exclude {
                it.moduleGroup == "io.netty" || it.moduleGroup == "org.slf4j"
            }
        }
    }

    register("copyJar") {
        pluginsDir?.let {
            doLast {
                copy {
                    from(shadowJar.get().archiveFile.get().asFile.absolutePath)
                    into(it)
                }
            }
        }

        outputs.upToDateWhen { false }
        mustRunAfter(shadowJar)
    }

    jar {
        enabled = false
        dependsOn(shadowJar)
        dependsOn("copyJar")
    }
}

tasks.named("build") {
    if (!noui) {
        dependsOn("zipPluginUI")
    }
}

tasks.named("processResources") {
    if (!noui) {
        dependsOn("zipPluginUI")
    }
}

publishing {
    repositories {
        maven {
            name = pluginId
            url = uri("https://maven.pkg.github.com/$organization/$pluginId")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME_GITHUB")
                password = project.findProperty("gpr.token") as String? ?: System.getenv("TOKEN_GITHUB")
            }
        }
    }

    publications {
        create<MavenPublication>("shadow") {
            project.extensions.configure<com.github.jengelman.gradle.plugins.shadow.ShadowExtension> {
                artifactId = pluginId
                component(this@create)
            }
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11)) // Java 11 toolchain
    }
}

kotlin {
    jvmToolchain(11)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// JUnit Platform for Jupiter tests; the test executor runs on JDK 21 like the host (the main
// classes compile to Java 11 bytecode, which JDK 21 runs fine).
tasks.named<Test>("test") {
    useJUnitPlatform()
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
}
