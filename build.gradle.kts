import java.net.URL
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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
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
    useJUnitPlatform { excludeTags("db", "e2e") }
    // ApiJarTest (B-22) inspects the thin API jar, so a plain `test` builds it first.
    dependsOn(apiJar)
    systemProperty("market.apiJar", layout.buildDirectory.file("api/$pluginId-api-$version.jar").get().asFile.absolutePath)
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
            require(names.none { it.startsWith("org/apache/pdfbox/") }) {
                "PDFBox must be relocated under com/panomc/plugins/market/shaded"
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
            // Minecraft side (19 section 2.3): once mc/ sources exist, the descriptors must be at the jar root and
            // no class under mc/ may reference platform classes.
            if (File(marketSrc, "mc").walkTopDown().any { it.isFile }) {
                for (f in listOf("plugin.yml", "bungee.yml", "velocity-plugin.json")) {
                    require(f in names) { "$f missing at the jar root" }
                }
                val needle = "com/panomc/platform/".toByteArray(Charsets.ISO_8859_1)
                names.filter { it.startsWith("com/panomc/plugins/market/mc/") && it.endsWith(".class") }.forEach { n ->
                    val bytes = z.getInputStream(z.getEntry(n)).use { it.readBytes() }
                    val s = String(bytes, Charsets.ISO_8859_1)
                    require(!s.contains(String(needle, Charsets.ISO_8859_1))) { "$n references com/panomc/platform/" }
                }
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyJar) }
