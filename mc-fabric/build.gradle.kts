import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// The Fabric jar of the Market component (19 section 2.1, 2.3, 3): a sibling Gradle subproject, only part of a standalone
// build with -Pfabric (settings.gradle.kts). It compiles the platform independent engine of the Spigot / BungeeCord /
// Velocity jar (src/mc/.../mc/core, shared as sources, never copied) together with its own `mc.fabric` classes into one
// jar `build/mc/pano-plugin-market-fabric-<version>.jar`, and applies pano-mc-plugin Fabric's relocations to its OWN classes
// without bundling a single library: Knot's class loader is flat and the Pano mod ships Kotlin, kotlinx, Gson ... under
// com.panomc.shadow.*, so every reference of this jar has to point there. The embedded platform build never sees it.

plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
    // 26.x: unobfuscated game; Loom 1.16+ (the same plugin version as pano-mc-plugin's Fabric module).
    id("net.fabricmc.fabric-loom") version "1.16.1"
}

group = "com.panomc.plugins"
version = rootProject.version

val panoCoreJar = rootProject.findProperty("panoCoreJar") as String? ?: System.getenv("PANO_CORE_JAR")
val luckPermsApiVersion: String by project
val panoMcVersion: String by project
val panoReleaseToken = (rootProject.findProperty("panoReleaseToken") as String?) ?: System.getenv("PANO_RELEASE_TOKEN")

// The same Core jar resolution as the root project (root build.gradle.kts publishes the outcome as `resolvedCoreJar`).
@Suppress("UNCHECKED_CAST")
val resolvedCoreJar: File? = rootProject.extra["resolvedCoreJar"] as File?

repositories {
    if (resolvedCoreJar == null) {
        exclusiveContent {
            forRepository {
                ivy {
                    name = "PanoMcReleases"
                    url = uri("https://github.com/PanoMC/pano-mc-plugin/releases/download")
                    patternLayout { artifact("v[revision]/pano-core-[revision].[ext]") }
                    metadataSources { artifact() }
                    if (!panoReleaseToken.isNullOrBlank()) {
                        credentials(HttpHeaderCredentials::class) {
                            name = "Authorization"
                            value = "Bearer $panoReleaseToken"
                        }
                        authentication { create<HttpHeaderAuthentication>("header") }
                    }
                }
            }
            filter { includeGroup("panomc.mc") }
        }
    }
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

loom {
    serverOnlyMinecraftJar()
}

dependencies {
    // Minecraft 26.1.2 (Mojang "26.1" release line), unobfuscated: no mappings.
    minecraft("com.mojang:minecraft:26.1.2")
    compileOnly("net.fabricmc:fabric-loader:0.19.2")
    compileOnly("net.fabricmc.fabric-api:fabric-api:0.146.1+26.1.2")

    // The Core API the engine links against; at run time it is the Pano mod's own (relocated) copy.
    if (resolvedCoreJar != null) {
        compileOnly(files(resolvedCoreJar))
    } else {
        compileOnly("panomc.mc:pano-core:$panoMcVersion")
    }
    compileOnly("net.luckperms:api:$luckPermsApiVersion") // provided by the LuckPerms mod

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.3")
    // FabricJarRules reads the class references of the real jar (never the Kotlin @Metadata strings, which no relocation rewrites).
    testImplementation("org.ow2.asm:asm-commons:9.9")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.3")
}

// Sources: the engine and the wire DTOs of the shared `mc` source set (never a copy) plus this module's own package.
kotlin {
    jvmToolchain(25)
    sourceSets.named("main") {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("src/mc/kotlin"))
        kotlin.include("com/panomc/plugins/market/mc/core/**", "com/panomc/plugins/market/mc/fabric/**")
    }
}

// Kotlin 2.2: no JVM_25 target yet; the Java byte level is 24 like pano-mc-plugin's Fabric module. Language / API 2.2 as
// the other platforms (19 section 2.1): the Kotlin of the Pano mod is what runs it.
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}
tasks.withType<JavaCompile>().configureEach { options.release = 24 }
tasks.named("compileKotlin") { dependsOn(":checkCoreSource") }

tasks.processResources {
    inputs.property("version", version.toString())
    filesMatching("fabric.mod.json") { expand(mapOf("version" to version.toString())) }
    // mc/config.yml and mc/lang/*.yml are read through the class loader (ResourceFiles); one copy for every platform.
    from(rootProject.layout.projectDirectory.dir("src/mc/resources")) { include("mc/**") }
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    // FabricJarRulesTest inspects the real jar, so it is built first.
    dependsOn(tasks.shadowJar)
    systemProperty("market.fabricJar", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("market.version", version.toString())
    systemProperty("market.mcResources", rootProject.layout.projectDirectory.dir("src/mc/resources").asFile.absolutePath)
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // The same gate as the other tiers: a run that executed nothing, or skipped anything, is a failure.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                println("TEST-SUMMARY fabricTest: executed=${result.testCount} failed=${result.failedTestCount} skipped=${result.skippedTestCount}")
                if (result.testCount == 0L || result.skippedTestCount > 0L) {
                    throw GradleException("fabricTest: executed=${result.testCount} skipped=${result.skippedTestCount}")
                }
            }
        }
    })
}

tasks {
    jar {
        archiveClassifier.set("slim")
    }

    shadowJar {
        // Nothing is bundled: only this module's own classes (the engine sources are part of them) are relocated.
        configurations = emptyList()

        manifest {
            attributes(mapOf("VERSION" to version.toString(), "Implementation-Title" to "pano-plugin-market-fabric"))
        }
        mergeServiceFiles()

        // pano-mc-plugin Fabric (Fabric/build.gradle.kts) relocates these in the Pano mod; the references of this jar must
        // point at the same names. Only the references that exist in our classes are rewritten, nothing is added.
        relocate("com.fasterxml.jackson", "com.panomc.shadow.jackson")
        relocate("io.netty", "vertx.io.netty")
        relocate("kotlin.", "com.panomc.shadow.kotlin.")
        relocate("kotlinx.", "com.panomc.shadow.kotlinx.")
        relocate("com.google.gson", "com.panomc.shadow.gson")
        relocate("org.springframework", "com.panomc.shadow.springframework")
        relocate("io.vertx", "com.panomc.shadow.vertx")
        relocate("com.github.jknack", "com.panomc.shadow.handlebars")
        relocate("org.apache.commons.logging", "com.panomc.shadow.commonslogging")
        relocate("org.aopalliance", "com.panomc.shadow.aopalliance")
        relocate("org.jetbrains.annotations", "com.panomc.shadow.jetbrainsannotations")
        relocate("org.intellij.lang.annotations", "com.panomc.shadow.intellijannotations")
        relocate("com.google.errorprone", "com.panomc.shadow.errorprone")
        relocate("com.typesafe", "com.panomc.shadow.typesafeconfig")
        // org.slf4j stays as it is on purpose (Pano's Fabric module does the same): it binds to Minecraft's own SLF4J.

        archiveClassifier.set("")
        archiveFileName.set("pano-plugin-market-fabric-$version.jar")
        destinationDirectory.set(rootProject.layout.buildDirectory.dir("mc"))
    }

    build {
        dependsOn(shadowJar)
        doLast {
            // The shaded jar is the only distributable.
            jar.get().archiveFile.get().asFile.delete()
        }
    }
}
