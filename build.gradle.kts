import java.net.URL
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("com.google.code.gson:gson:2.11.0") }
}

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
val panoVersion: String by project
val bootstrap = (project.findProperty("bootstrap") as String?)?.toBoolean() ?: false
// Standalone builds resolve the platform fat jar (15 section 2.1): "release" = Ivy pattern on the GitHub release
// asset (default), "jitpack" = -PpanoSource=jitpack, or a local jar with -PpanoJar=/path/Pano-<version>.jar.
val panoSource = (project.findProperty("panoSource") as String?) ?: "release"
val panoJar = project.findProperty("panoJar") as String?
val panoReleaseToken = (project.findProperty("panoReleaseToken") as String?) ?: System.getenv("PANO_RELEASE_TOKEN")
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
    if (!bootstrap && panoJar == null && panoSource == "release") {
        exclusiveContent {
            forRepository {
                ivy {
                    name = "PanoReleases"
                    url = uri("https://github.com/PanoMC/Pano/releases/download")
                    patternLayout { artifact("v[revision]/Pano-[revision].[ext]") }
                    metadataSources { artifact() }
                    // Anonymous while PanoMC/Pano is public; CI may pass a read-only token.
                    if (!panoReleaseToken.isNullOrBlank()) {
                        credentials(HttpHeaderCredentials::class) {
                            name = "Authorization"
                            value = "Bearer $panoReleaseToken"
                        }
                        authentication { create<HttpHeaderAuthentication>("header") }
                    }
                }
            }
            filter { includeGroup("panomc.release") }
        }
    }
    mavenCentral()
    maven("https://jitpack.io")
}

// Fake payment provider plugin (T3, 17 section 6): own source set, own jar, never part of the market jar.
val fakeProvider: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}

// Minecraft-side component (19 section 2.1): the same jar is also the plugin on Spigot / Paper / Folia, BungeeCord and
// Velocity. `mc` (Java 11) and `mcVelocity` (Java 17) are compiled into the market jar, `mcTest` (T7) is not.
// They never see the platform or `main`; `main` never sees them (verifyJar scans both directions).
val spigotApiVersion: String by project
val bungeecordApiVersion: String by project
val velocityApiVersion: String by project
val luckPermsApiVersion: String by project
val vaultApiVersion: String by project
val placeholderApiVersion: String by project
val panoMcVersion: String by project
val panoCoreJar = project.findProperty("panoCoreJar") as String? ?: System.getenv("PANO_CORE_JAR")

val mc: SourceSet by sourceSets.creating
val mcVelocity: SourceSet by sourceSets.creating
val mcTest: SourceSet by sourceSets.creating

// pano-mc-plugin Core (pano-core-<version>.jar), resolved like the platform artifact (15 section 2.1): an explicit jar
// (-PpanoCoreJar / PANO_CORE_JAR) wins, then the Core jar built in the umbrella checkout
// (<umbrella>/pano-mc-plugin/Core/build/libs, found by walking up from the project directory, so an embedded build, a
// standalone checkout and a stream worktree all find it without any flag), then the Ivy pattern on the pano-mc-plugin
// release asset as the last resort.
fun findUmbrellaCoreJar(): File? {
    val starts = listOf(projectDir, rootProject.projectDir).distinct()
    for (start in starts) {
        var dir: File? = start
        for (i in 0 until 7) {
            val libs = File(dir ?: break, "pano-mc-plugin/Core/build/libs")
            val jar = libs.listFiles { f -> f.isFile && f.name.startsWith("pano-core-") && f.name.endsWith(".jar") }
                ?.maxByOrNull { it.lastModified() }
            if (jar != null) return jar
            dir = dir?.parentFile
        }
    }
    return null
}

val resolvedCoreJar: File? = when {
    !panoCoreJar.isNullOrBlank() -> File(panoCoreJar)
    else -> findUmbrellaCoreJar()
}
// The Fabric subproject (-Pfabric, mc-fabric/) compiles against the same Core jar.
extra["resolvedCoreJar"] = resolvedCoreJar

// The release that panoMcVersion pins has to carry the pano-core asset. 1.0.0-alpha.66 does not (the asset is attached
// by a later release, REL-03); resolving it through Ivy can only fail, so say so instead of a bare 404.
val coreReleasesWithoutAsset = setOf("1.0.0-alpha.66")
val checkCoreSource by tasks.registering {
    val usesIvy = resolvedCoreJar == null
    val pin = panoMcVersion
    doLast {
        if (usesIvy && pin in coreReleasesWithoutAsset) {
            throw GradleException(
                "pano-core $pin has no release asset and no local Core jar was found: build it in pano-mc-plugin " +
                    "(./gradlew :Core:build) or pass -PpanoCoreJar=<path to pano-core-*.jar> (or PANO_CORE_JAR)."
            )
        }
    }
}

if (resolvedCoreJar == null) {
    repositories {
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
}
repositories {
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.helpch.at/releases/")
}

dependencies {
    // Platform classes for compiling (compileOnly: the host provides them at run time).
    when {
        bootstrap -> compileOnly(project(mapOf("path" to ":Pano")))
        panoJar != null -> compileOnly(files(panoJar))
        panoSource == "jitpack" -> compileOnly("com.github.panomc:pano:v$panoVersion")
        else -> compileOnly("panomc.release:pano:$panoVersion") // no leading "v"
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

    // spi.testkit (ProviderContractTest, ShippingProviderContractTest) is part of the API jar and uses JUnit; it is
    // compiled against the API only, the plugin that runs the contract brings its own JUnit (02 section 9).
    compileOnly("org.junit.jupiter:junit-jupiter-api:5.13.3")

    // Invoice PDFs (12 section 8.1, MK-144): bundled and relocated by shadowJar (relocate block below). easytable declares
    // pdfbox 3.0.2; the higher 3.0.8 wins conflict resolution, the explicit coordinates make that visible. No BouncyCastle.
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("com.github.vandeseer:easytable:1.0.2")

    // Same JUnit setup as the host (see Pano/build.gradle.kts).
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.13.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.3")
    // Gradle 9 requires the JUnit Platform launcher on the test runtime classpath explicitly.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.3")

    // Host classes for T1/T2/T4. T0 must not need them (T0ClasspathTest).
    if (bootstrap) {
        testImplementation(project(mapOf("path" to ":Pano")))
        testImplementation("io.vertx:vertx-web:$vertxVersion")
        testImplementation("io.vertx:vertx-web-client:$vertxVersion")
        testImplementation("io.vertx:vertx-mysql-client:$vertxVersion")
        testImplementation("io.vertx:vertx-lang-kotlin:$vertxVersion")
        testImplementation("io.vertx:vertx-lang-kotlin-coroutines:$vertxVersion")
        testImplementation("io.vertx:vertx-json-schema:$vertxVersion")
        testImplementation("org.springframework:spring-context:$springContextVersion")
        testImplementation("com.google.code.gson:gson:$gsonVersion")
        testImplementation("org.pf4j:pf4j:$pf4jVersion")
    } else {
        // The fat jar already contains Vert.x 5.0.1, Gson, Spring, pf4j and the coroutines; a second copy
        // of any of them breaks linkage, so no separate io.vertx:* test dependency is added here.
        when {
            panoJar != null -> testImplementation(files(panoJar))
            panoSource == "jitpack" -> testImplementation("com.github.panomc:pano:v$panoVersion")
            else -> testImplementation("panomc.release:pano:$panoVersion")
        }
    }
    testImplementation(fakeProvider.output) // the fake provider is exercised in-process by T1
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

// Minecraft-side dependencies: all compileOnly, the servers provide them (Kotlin stdlib comes through the Pano plugin).
dependencies {
    if (resolvedCoreJar != null) {
        "mcCompileOnly"(files(resolvedCoreJar))
    } else {
        "mcCompileOnly"("panomc.mc:pano-core:$panoMcVersion") // no leading "v"
    }
    "mcCompileOnly"(kotlin("stdlib-jdk8"))
    "mcCompileOnly"("org.spigotmc:spigot-api:$spigotApiVersion")
    "mcCompileOnly"("net.md-5:bungeecord-api:$bungeecordApiVersion")
    "mcCompileOnly"("net.luckperms:api:$luckPermsApiVersion")
    "mcCompileOnly"("com.github.MilkBowl:VaultAPI:$vaultApiVersion") { isTransitive = false }
    "mcCompileOnly"("me.clip:placeholderapi:$placeholderApiVersion") { isTransitive = false }

    "mcVelocityCompileOnly"(mc.output)
    "mcVelocityCompileOnly"("com.velocitypowered:velocity-api:$velocityApiVersion")

    "mcTestImplementation"(mc.output)
    "mcTestImplementation"(mcVelocity.output)
    "mcTestImplementation"("com.velocitypowered:velocity-api:$velocityApiVersion")
    "mcTestImplementation"("org.junit.jupiter:junit-jupiter-api:5.13.3")
    "mcTestRuntimeOnly"("org.junit.jupiter:junit-jupiter-engine:5.13.3")
    "mcTestRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.13.3")
}

// mcVelocity and mcTest see everything mc compiles against (Core, the server APIs).
configurations["mcVelocityCompileOnly"].extendsFrom(configurations["mcCompileOnly"])
configurations["mcTestImplementation"].extendsFrom(configurations["mcCompileOnly"])
// spigot-api 1.8.8 pulls SnakeYAML 1.x and bungeecord-api 1.21 SnakeYAML 2.x; one version for the tests.
listOf("mcTestCompileClasspath", "mcTestRuntimeClasspath").forEach { n ->
    configurations.named(n) { resolutionStrategy.force("org.yaml:snakeyaml:2.2") }
}

// mc / mcVelocity / mcTest compile options (19 section 2.1): Java 11 with the Java 11 API surface for mc, Java 17 for
// mcVelocity (velocity-api class files are major 61), Kotlin language / API pinned to the oldest supported
// pano-mc-plugin line (2.2) because the stdlib on the server is the one Pano ships.
// kapt (pf4j @Extension) is for `main` only; the mc source sets use no annotation processor.
tasks.matching { it.name.matches(Regex("kapt(GenerateStubs)?Mc.*Kotlin")) }.configureEach { enabled = false }

fun configureMcJvm(set: SourceSet, jdk: Int, release11: Boolean) {
    val cap = set.name.replaceFirstChar { it.uppercase() }
    tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compile${cap}Kotlin") {
        kotlinJavaToolchain.toolchain.use(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdk)) })
        compilerOptions {
            jvmTarget.set(if (jdk == 17) org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 else org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
            if (release11) freeCompilerArgs.add("-Xjdk-release=11")
        }
    }
    tasks.named<JavaCompile>("compile${cap}Java") {
        javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(jdk)) })
    }
    listOf(set.compileClasspathConfigurationName, set.runtimeClasspathConfigurationName).forEach { name ->
        configurations.named(name) {
            attributes.attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, jdk)
        }
    }
}
configureMcJvm(mc, 11, true)
configureMcJvm(mcVelocity, 17, false)
configureMcJvm(mcTest, 17, false)
tasks.named("compileMcKotlin") { dependsOn(checkCoreSource) }

// Descriptors at the jar root get the plugin version (same mechanism as pano-plugin-premium-login).
tasks.named<ProcessResources>("processMcResources") {
    inputs.property("version", version.toString())
    filesMatching(listOf("plugin.yml", "bungee.yml", "velocity-plugin.json")) {
        expand(mapOf("version" to version.toString()))
    }
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

        // Minecraft-side component (19 section 2.2): classes, descriptors and resources at the jar root.
        from(mc.output)
        from(mcVelocity.output)

        dependencies {
            exclude(dependency("io.vertx:vertx-core"))
            exclude {
                it.moduleGroup == "io.netty" || it.moduleGroup == "org.slf4j"
            }
        }

        // PDF stack (12 section 8.1): relocated so a payment or shipping plugin that brings its own PDFBox cannot clash with
        // market's (payment plugins see market classes through the dependency class loader). Shadow rewrites resource paths
        // too (org/apache/pdfbox/resources/... becomes the shaded path); ShadedInvoiceRendererTest proves that on the built jar.
        relocate("org.apache.pdfbox", "com.panomc.plugins.market.shaded.org.apache.pdfbox")
        relocate("org.apache.fontbox", "com.panomc.plugins.market.shaded.org.apache.fontbox")
        relocate("org.apache.commons.logging", "com.panomc.plugins.market.shaded.org.apache.commons.logging")
        relocate("org.vandeseer.easytable", "com.panomc.plugins.market.shaded.org.vandeseer.easytable")

        // Resources the renderer can never read (16 section 6.2 size budget): market only writes PDFs with embedded subset fonts, so the
        // predefined CJK CMaps of FontBox (1.2 MB, they exist to read other people's PDFs; Identity-H / -V stay) and the Liberation fallback
        // fonts of PDFBox (market installs a font mapper that never looks at fonts it did not embed) are dead weight.
        exclude { it.relativePath.pathString.startsWith("org/apache/fontbox/cmap/") && !it.name.endsWith(".class") && !it.name.startsWith("Identity-") }
        exclude { it.relativePath.pathString.startsWith("org/apache/pdfbox/resources/ttf/") }
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

// Thin API jar for standalone plugin builds (02 section 9, 16 section 6.1): com/panomc/plugins/market/spi/** (testkit
// included) plus the Kotlin module file for the top-level settingsSchema { } DSL. Written to build/api, not build/libs,
// so every glob over build/libs/pano-plugin-market-*.jar (store upload, E2E instance, copyJar) sees only the plugin jar.
val apiJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Thin SPI jar attached to every market release for standalone plugin builds."
    archiveFileName.set("$pluginId-api-$version.jar")
    destinationDirectory.set(layout.buildDirectory.dir("api"))
    from(sourceSets.main.get().output.classesDirs) {
        include("com/panomc/plugins/market/spi/**")
        include("META-INF/*.kotlin_module")
    }
    includeEmptyDirs = false
    manifest {
        attributes("Implementation-Title" to "$pluginId-api", "Implementation-Version" to version.toString())
    }
}

tasks.named("build") {
    dependsOn(apiJar)
    if (!noui) {
        dependsOn("zipPluginUI")
    }
}

// Locale sources are fragments (src/locales/{core,panel,theme}/<lang>.json); the platform wants one
// file per language, so this deep-merges them into the git-ignored src/main/resources/locales/.
val mergeLocales by tasks.registering {
    val srcDir = layout.projectDirectory.dir("src/locales")
    val outDir = layout.projectDirectory.dir("src/main/resources/locales")
    inputs.dir(srcDir)
    outputs.dir(outDir)

    doLast {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        val langs = listOf("tr", "en-US", "ru")
        val fragments = srcDir.asFile.listFiles { f -> f.isDirectory }!!.sortedBy { it.name }
        val merged = mutableMapOf<String, com.google.gson.JsonObject>()

        fun leaves(e: com.google.gson.JsonElement, path: String, out: MutableSet<String>) {
            if (e.isJsonObject) e.asJsonObject.entrySet().forEach { leaves(it.value, if (path.isEmpty()) it.key else "$path.${it.key}", out) }
            else out.add(path)
        }

        fun merge(into: com.google.gson.JsonObject, from: com.google.gson.JsonObject, path: String, where: String) {
            for ((k, v) in from.entrySet()) {
                val p = if (path.isEmpty()) k else "$path.$k"
                val existing = into.get(k)
                when {
                    existing == null -> into.add(k, v)
                    existing.isJsonObject && v.isJsonObject -> merge(existing.asJsonObject, v.asJsonObject, p, where)
                    else -> throw GradleException("mergeLocales: duplicate leaf key '$p' ($where)")
                }
            }
        }

        for (lang in langs) {
            val root = com.google.gson.JsonObject()
            for (dir in fragments) {
                val f = File(dir, "$lang.json")
                if (!f.exists()) throw GradleException("mergeLocales: missing ${dir.name}/$lang.json")
                merge(root, com.google.gson.JsonParser.parseString(f.readText()).asJsonObject, "", "${dir.name}/$lang.json")
            }
            merged[lang] = root
        }

        val keySets = langs.associateWith { l -> mutableSetOf<String>().also { leaves(merged[l]!!, "", it) } }
        val base = keySets.getValue(langs.first())
        for (l in langs.drop(1)) {
            val missing = base - keySets.getValue(l)
            val extra = keySets.getValue(l) - base
            if (missing.isNotEmpty() || extra.isNotEmpty()) {
                throw GradleException(
                    "mergeLocales: key set of $l differs from ${langs.first()}: missing=${missing.sorted().take(20)} extra=${extra.sorted().take(20)}"
                )
            }
        }

        outDir.asFile.mkdirs()
        langs.forEach { File(outDir.asFile, "$it.json").writeText(gson.toJson(merged[it]) + "\n") }
    }
}

tasks.named("processResources") {
    dependsOn(mergeLocales)
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

// (mcVelocity and mcTest set their own target in configureMcJvm: Java 17.)
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (!name.contains("McVelocity") && !name.contains("McTest")) {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

// Compiler options shared by main, test and the fake provider source set.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    // Real JVM default methods in SPI interfaces (00 section 10): methods can be added without breaking older plugin jars.
    compilerOptions.freeCompilerArgs.add("-jvm-default=enable")
}

// Tiers (17 section 2/3.2): `test` = T0 + T1 (no database, no instance); `dbTest` = T2 (real MariaDB, tag "db");
// `e2eTest` = T4 (tag "e2e"). The test JVM is JDK 21 like the host; main classes are Java 11 bytecode.
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("db", "e2e", "shaded") }
    // ApiJarTest (B-22) inspects the thin API jar, so a plain `test` builds it first.
    dependsOn(apiJar)
    systemProperty("market.apiJar", layout.buildDirectory.file("api/$pluginId-api-$version.jar").get().asFile.absolutePath)
    // The wire fixtures the Minecraft-side WireParityTest (MC-U7) checks mc.core.wire against; the Pano-side event
    // tests read the same files so both ends are held to one document (19 section 13).
    systemProperty("market.wireFixtures", layout.projectDirectory.dir("src/mcTest/resources/wire").asFile.absolutePath)
}

val dbTest by tasks.registering(Test::class) {
    group = "verification"
    description = "T2: DAO, schema, migration and service tests against a real MariaDB (PANO_IT_MARIADB)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("db") }
    maxParallelForks = 1
    // The gate lives in doFirst and in the TEST-SUMMARY listener; the database is not a task input, so a green run
    // must never be replayed as UP-TO-DATE or FROM-CACHE (17 section 3.2).
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
    doFirst {
        require(!System.getenv("PANO_IT_MARIADB").isNullOrBlank()) {
            "dbTest needs PANO_IT_MARIADB=host:port and PANO_IT_MARIADB_PASSWORD"
        }
    }
}

// MC-09 (19 section 13): `FakeMcServer` runs the REAL `mc.core` component inside the e2e JVM, over a real socket to the instance. The compiled `mc`
// source set and the five Core classes the wire types extend (`PlatformRequest`, `PlatformMessage*`, `TextUtil`) are therefore visible to the T4 tests
// (the Core jar itself is a fat jar with Vert.x, Spring, ... that must never sit next to the host jar, so just those classes are cut out of it).
// They are on the classpath of every test task, not of `e2eTest` alone: JUnit's discovery reflects over `McE2E` in `test` and `dbTest` too and a
// method signature naming an `mc` type would fail the whole task with NoClassDefFoundError. The market jar never carries any of this.
val mcE2eCoreDir = layout.buildDirectory.dir("mc-e2e-core")
val mcE2eCoreClasses by tasks.registering(Sync::class) {
    // The Core jar the mc source set compiles against: the local file, or the Ivy artifact when none was found, so both paths behave the same.
    from({
        val core = resolvedCoreJar
            ?: configurations["mcCompileClasspath"].files.single { it.name.startsWith("pano-core-") }
        zipTree(core)
    }) {
        include("com/panomc/plugins/pano/core/platform/PlatformRequest*.class")
        include("com/panomc/plugins/pano/core/platform/PlatformMessage*.class")
        include("com/panomc/plugins/pano/core/util/TextUtil*.class")
    }
    into(mcE2eCoreDir)
    dependsOn(checkCoreSource)
}
dependencies {
    "testImplementation"(mc.output)
    "testImplementation"(files(mcE2eCoreDir).builtBy(mcE2eCoreClasses))
}

val e2eTest by tasks.registering(Test::class) {
    group = "verification"
    description = "T4: end-to-end scenarios against the isolated instance (scripts/e2e-instance.sh)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
    maxParallelForks = 1
    outputs.upToDateWhen { false } // same reason as dbTest: the instance and the database are not task inputs
    outputs.cacheIf { false }
    dependsOn("fakeProviderJar")
    systemProperty("market.e2e.fakeJar", layout.buildDirectory.file("fake/pano-plugin-market-fake-$version.jar").get().asFile.absolutePath)
    doFirst {
        require(!System.getenv("PANO_IT_MARIADB").isNullOrBlank() && !System.getenv("MARKET_E2E_URL").isNullOrBlank()) {
            "e2eTest needs PANO_IT_MARIADB and MARKET_E2E_URL (start the instance with scripts/e2e-instance.sh)"
        }
    }
}

tasks.register<Test>("mcTest") {
    group = "verification"
    description = "T7: unit tests of the Minecraft-side component (src/mcTest) with fake platform adapters."
    testClassesDirs = mcTest.output.classesDirs
    classpath = mcTest.runtimeClasspath
    useJUnitPlatform()
    maxParallelForks = 1
    // JarRulesTest inspects the real market jar, so the jar is built first (verifyJar is the Gradle twin of those checks).
    dependsOn(tasks.shadowJar)
    systemProperty("market.jar", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("market.version", version.toString())
    systemProperty("market.mcResources", layout.projectDirectory.dir("src/mc/resources").asFile.absolutePath)
    systemProperty("market.wireFixtures", layout.projectDirectory.dir("src/mcTest/resources/wire").asFile.absolutePath)
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
// T-PDF-5 (12 section 12): the invoice renderer run from the BUILT shadowJar in a fresh class loader, so relocated classes and
// relocated resource paths are exercised for real. Part of `check`, never of `test` (it needs the jar).
val shadedTest by tasks.registering(Test::class) {
    group = "verification"
    description = "T-PDF-5: renders an invoice with the shaded PDF stack of the built market jar."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("shaded") }
    maxParallelForks = 1
    dependsOn(tasks.shadowJar)
    systemProperty("market.jar", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
tasks.named("check") { dependsOn(shadedTest) }

tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    reports.junitXml.required.set(true)

    // A gated task that executed nothing, or skipped anything, is a failure: a missing database or a @Disabled
    // class can never look like a green run.
    val taskName = name
    val gated = taskName != "test"
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                println("TEST-SUMMARY $taskName: executed=${result.testCount} failed=${result.failedTestCount} skipped=${result.skippedTestCount}")
                if (gated && (result.testCount == 0L || result.skippedTestCount > 0L)) {
                    throw GradleException("$taskName: executed=${result.testCount} skipped=${result.skippedTestCount}")
                }
            }
        }
    })
}

val fakeProviderJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Packages the T3 fake payment provider plugin (tests only; never in build/libs)."
    from(fakeProvider.output)
    archiveFileName.set("pano-plugin-market-fake-$version.jar")
    destinationDirectory.set(layout.buildDirectory.dir("fake")) // never build/libs: the release uploads build/libs/*.jar
    manifest {
        attributes(
            "id" to "pano-plugin-market-fake",
            "name" to "Market fake gateway (tests only)",
            "main-class" to "com.panomc.plugins.marketfake.FakePlugin",
            "version" to version,
            "pano-version" to pluginPanoVersion,
            "developer" to "Pano",
            "dependencies" to "pano-plugin-market"
        )
    }
}

// Part of `check`: the shipped jar must not carry host libraries, the fake provider or too-new bytecode.
val verifyJar by tasks.registering {
    group = "verification"
    description = "Checks the market jar: no host or fake classes, SPI present, Java 11 bytecode."
    dependsOn(tasks.shadowJar)
    val marketSrc = layout.projectDirectory.dir("src/main/kotlin/com/panomc/plugins/market").asFile
    doLast {
        val jar = tasks.shadowJar.get().archiveFile.get().asFile
        ZipFile(jar).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            val forbidden = listOf(
                "com/panomc/plugins/marketfake/", "com/panomc/platform/", "kotlin/", "kotlinx/coroutines/",
                "io/vertx/", "org/springframework/", "com/google/gson/"
            )
            val hit = names.filter { n -> forbidden.any { n.startsWith(it) } }
            require(hit.isEmpty()) { "market jar contains forbidden classes: ${hit.take(5)}" }
            // The SPI package arrives with the payment SPI slice; once its sources exist the jar must carry it.
            if (File(marketSrc, "spi").walkTopDown().any { it.isFile }) {
                require(names.any { it.startsWith("com/panomc/plugins/market/spi/") }) { "SPI missing from the jar" }
            }
            // MP-J06 (16 section 6.2): the store takes 10 MB; PDFBox + fonts are shaded in, so size is checked on every build.
            require(jar.length() <= 9_500_000L) { "market jar is ${jar.length()} bytes (limit 9 500 000, 16 section 6.2)" }
            // PDF stack (12 section 8.1): only under the shaded prefix, never at its original path.
            val unshaded = names.filter { n ->
                n.startsWith("org/apache/pdfbox/") || n.startsWith("org/apache/fontbox/") ||
                    n.startsWith("org/apache/commons/logging/") || n.startsWith("org/vandeseer/")
            }
            require(unshaded.isEmpty()) {
                "PDFBox, FontBox, commons-logging and easytable must be relocated under com/panomc/plugins/market/shaded: ${unshaded.take(5)}"
            }
            require(names.any { it.startsWith("com/panomc/plugins/market/shaded/org/apache/pdfbox/") }) { "shaded PDFBox is missing from the jar" }
            require("fonts/NotoSans-Regular.ttf" in names && "fonts/NotoSans-Bold.ttf" in names && "fonts/OFL.txt" in names) {
                "the Noto Sans fonts and OFL.txt must be in the jar"
            }
            // Java 11 bytecode (00 section 10): class major version of every market class must be <= 55
            // (mc.velocity is Java 17, 19 section 2).
            z.entries().asSequence().filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }.forEach { e ->
                val major = z.getInputStream(e).use { s ->
                    val b = ByteArray(8)
                    var read = 0
                    while (read < 8) {
                        val r = s.read(b, read, 8 - read)
                        require(r > 0) { "${e.name} is truncated" }
                        read += r
                    }
                    ((b[6].toInt() and 0xff) shl 8) or (b[7].toInt() and 0xff)
                }
                val limit = if (e.name.startsWith("com/panomc/plugins/market/mc/velocity/")) 61 else 55
                require(major <= limit) { "${e.name} is class major $major (> $limit)" }
            }
            // Minecraft side (19 section 2): once mc sources exist the descriptors sit at the jar root with the
            // version filled in, mc never references the platform or a market package outside mc.**, main never
            // references mc, and neither the Minecraft APIs nor Core are bundled.
            val mcSrc = layout.projectDirectory.dir("src/mc/kotlin").asFile
            val mcVelocitySrc = layout.projectDirectory.dir("src/mcVelocity/kotlin").asFile
            if (mcSrc.walkTopDown().any { it.isFile } || layout.projectDirectory.dir("src/mc/resources").asFile.walkTopDown().any { it.isFile }) {
                for (f in listOf("plugin.yml", "bungee.yml", "velocity-plugin.json")) {
                    require(f in names) { "$f missing at the jar root" }
                    val text = z.getInputStream(z.getEntry(f)).use { String(it.readBytes(), Charsets.UTF_8) }
                    require(text.contains(version.toString())) { "$f does not carry the version ${version}" }
                    require(!text.contains("\${")) { "$f still holds an unexpanded placeholder" }
                }
                val bundled = listOf(
                    "com/panomc/plugins/pano/core/", "org/bukkit/", "org/spigotmc/", "net/md_5/", "net/luckperms/",
                    "com/velocitypowered/", "net/milkbowl/", "me/clip/"
                )
                val bundledHit = names.filter { n -> bundled.any { n.startsWith(it) } }
                require(bundledHit.isEmpty()) { "market jar bundles Minecraft-side libraries: ${bundledHit.take(5)}" }
                val platformRef = "com/panomc/platform/"
                val mcRef = "com/panomc/plugins/market/mc/"
                val otherMarketRef = Regex("com/panomc/plugins/market/(?!mc/)")
                names.filter { it.endsWith(".class") && it.startsWith("com/panomc/plugins/market/") }.forEach { n ->
                    val body = String(z.getInputStream(z.getEntry(n)).use { it.readBytes() }, Charsets.ISO_8859_1)
                    if (n.startsWith(mcRef)) {
                        require(!body.contains(platformRef)) { "$n references $platformRef" }
                        require(!otherMarketRef.containsMatchIn(body)) { "$n references a market package outside mc.**" }
                    } else {
                        require(!body.contains(mcRef)) { "$n (main) references $mcRef" }
                    }
                }
                // A platform whose sources exist must have the main class its descriptor names in the jar.
                val descriptors = mapOf(
                    "plugin.yml" to Regex("(?m)^main:\\s*(\\S+)"),
                    "bungee.yml" to Regex("(?m)^main:\\s*(\\S+)"),
                    "velocity-plugin.json" to Regex("\"main\"\\s*:\\s*\"([^\"]+)\"")
                )
                val platformDirs = mapOf(
                    "plugin.yml" to File(mcSrc, "com/panomc/plugins/market/mc/spigot"),
                    "bungee.yml" to File(mcSrc, "com/panomc/plugins/market/mc/bungee"),
                    "velocity-plugin.json" to File(mcVelocitySrc, "com/panomc/plugins/market/mc/velocity")
                )
                descriptors.forEach { (file, regex) ->
                    if (platformDirs.getValue(file).walkTopDown().any { it.isFile }) {
                        val text = z.getInputStream(z.getEntry(file)).use { String(it.readBytes(), Charsets.UTF_8) }
                        val main = regex.find(text)?.groupValues?.get(1)
                        require(main != null) { "$file names no main class" }
                        require(names.contains(main.replace('.', '/') + ".class")) { "$file names $main, which is not in the jar" }
                    }
                }
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyJar, "mcTest") }

// Fabric jar (19 section 2.1, 2.3): only with -Pfabric (always in CI releases). `mc-fabric` is a sibling subproject that
// settings.gradle.kts includes for -Pfabric only; its jar is bundled into the market jar as the resource
// mc/pano-plugin-market-fabric.jar, which GET /api/panel/market/mc-component/download?platform=fabric streams. Without
// -Pfabric nothing of this exists: the jar has no such entry (the endpoint answers 404 then) and the embedded platform
// build (GM jar) never sees mc-fabric.
//
// The jar is appended to the finished market jar instead of being put into the resources: shadow unpacks every *.jar
// file it is given as a source, which would merge the Fabric classes into the market jar.
val fabricJarEntry = "mc/pano-plugin-market-fabric.jar"
val fabricJarFile = layout.buildDirectory.file("mc/pano-plugin-market-fabric-$version.jar")
// Whether the Fabric jar is bundled is an input of shadowJar: a build without -Pfabric after one with it must write a clean jar.
tasks.shadowJar { inputs.property("withFabric", project.hasProperty("fabric")) }
if (project.hasProperty("fabric")) {
    tasks.shadowJar {
        dependsOn(":mc-fabric:shadowJar")
        inputs.file(fabricJarFile).withPropertyName("fabricJar")
        doLast {
            val target = archiveFile.get().asFile.toPath()
            FileSystems.newFileSystem(target).use { fs ->
                Files.createDirectories(fs.getPath("mc"))
                Files.copy(
                    fabricJarFile.get().asFile.toPath(), fs.getPath(fabricJarEntry), StandardCopyOption.REPLACE_EXISTING
                )
            }
        }
    }
    // `build` also runs the tests and the jar rules of the Fabric jar.
    tasks.named("build") { dependsOn(":mc-fabric:build") }
}

// The bundle is part of the market jar exactly when -Pfabric is given, and then it is the jar `:mc-fabric:build` wrote.
tasks.named("verifyJar") {
    val withFabric = project.hasProperty("fabric")
    doLast {
        val jar = tasks.shadowJar.get().archiveFile.get().asFile
        ZipFile(jar).use { z ->
            val entry = z.getEntry(fabricJarEntry)
            if (!withFabric) {
                require(entry == null) { "$fabricJarEntry is in the jar of a build without -Pfabric" }
            } else {
                require(entry != null) { "$fabricJarEntry is missing from the jar of a -Pfabric build" }
                val bundled = z.getInputStream(entry).use { it.readBytes() }
                val built = fabricJarFile.get().asFile.readBytes()
                require(bundled.contentEquals(built)) { "$fabricJarEntry differs from build/mc/pano-plugin-market-fabric-$version.jar" }
                require(bundled.size in 1_000..2_000_000) { "$fabricJarEntry has an implausible size ${bundled.size}" }
                require(z.entries().asSequence().none { it.name.startsWith("com/panomc/plugins/market/mc/fabric/") || it.name == "fabric.mod.json" }) {
                    "the Fabric classes or fabric.mod.json were unpacked into the market jar"
                }
            }
        }
    }
}
