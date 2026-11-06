plugins {
    alias(libs.plugins.multiplatform).apply(false)
    alias(libs.plugins.android.library).apply(false)
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.paparazzi) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.poko) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/com/composables/**")
        ktlint("1.5.0")
            .setEditorConfigPath("$rootDir/.editorconfig")
            .editorConfigOverride(
                mapOf(
                    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                    "ktlint_standard_filename" to "disabled",
                ),
            )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint("1.5.0")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ─── Verification entry point ───────────────────────────────────────────────────────────────────
// `./gradlew verifyAll` runs every check CI runs. scripts/verify.sh calls the same task and narrows
// it with -Pcamerak.verify.modules=<a,b> and -Pcamerak.verify.targets=<desktop,android,ios,
// screenshots,lint,sample>; with neither property set, everything runs.

val libraryModules =
    listOf(
        "cameraK-core",
        "cameraK",
        "ImageSaverPlugin",
        "qrScannerPlugin",
        "ocrPlugin",
        "analyzerPlugin",
    )
// Published modules that are not libraries: test fixtures, which apps add to test source sets only.
// Every gate that holds a published module reads publishedModules, so a fixture module is held to all
// of them; checkTestOnlyDependencies names the one exception.
val testFixtureModules = listOf("cameraK-testing")
val publishedModules = libraryModules + testFixtureModules
val screenshotModule = "cameraKScreenshotTests"

// Every check the lint target runs is a task in this group, in any project: verifyAll runs them all,
// and writes their paths for scripts/verify_report.py to require. Adding a check means registering
// it in this group and nothing else. Modules read it as rootProject.extra["lintGroup"].
val lintGroup = "camerak lint"
extra["lintGroup"] = lintGroup

tasks.named("spotlessCheck") { group = lintGroup }

val bomModule = "camerak-bom"

// Every published module states the visibility of each declaration (explicit API mode), and checks its
// public ABI against the dumps committed under its api/ directory: a JVM dump per JVM target and one klib
// dump for the native targets. checkKotlinAbi runs in the lint target; after a reviewed change,
// ./gradlew updateKotlinAbi rewrites the dumps (CONTRIBUTING.md).
subprojects {
    if (name !in publishedModules) return@subprojects
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
            explicitApi()
            @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
            abiValidation()
        }
        tasks.matching { it.name == "checkKotlinAbi" }.configureEach { group = lintGroup }
    }
}

// The 1.x artifact ids 2.0 renamed (design §9.4), each to the module whose POM_ARTIFACT_ID is its 2.0
// id. Each old id is a POM-only project under relocations/ whose POM relocates it there, so a build
// that still names the old id resolves the new one. video_recorder_plugin has no 2.0 successor.
val relocatedArtifacts =
    mapOf(
        "qr_scanner_plugin" to "qrScannerPlugin",
        "ocr_plugin" to "ocrPlugin",
        "analyzer_plugin" to "analyzerPlugin",
        "image_saver_plugin" to "ImageSaverPlugin",
    )

fun relocationProject(oldId: String) = findProject(":relocations:$oldId")

// The artifact id a module publishes, from its own gradle.properties, where vanniktech reads it.
fun pomArtifactId(module: String): String =
    java.util
        .Properties()
        .apply {
            layout.projectDirectory
                .file("$module/gradle.properties")
                .asFile
                .reader()
                .use(::load)
        }.getProperty("POM_ARTIFACT_ID")
        .orEmpty()

// The libraries ship only bytedeco's Java bindings. Whatever runs them here (desktop tests, the
// Sample) adds the natives for this machine, under JavaCPP's platform name, as
// compose.desktop.currentOs does for Skiko. Modules read it as rootProject.extra["javacppPlatform"].
extra["javacppPlatform"] =
    run {
        val os = System.getProperty("os.name").lowercase()
        val family =
            when {
                os.startsWith("mac") -> "macosx"
                os.startsWith("windows") -> "windows"
                os.startsWith("linux") -> "linux"
                else -> throw GradleException("No bytedeco natives for os.name '$os'")
            }
        val arch =
            when (val name = System.getProperty("os.arch")) {
                "aarch64", "arm64" -> "arm64"
                "amd64", "x86_64" -> "x86_64"
                else -> throw GradleException("No bytedeco natives for os.arch '$name'")
            }
        "$family-$arch"
    }

// One version and group for every published module, from gradle.properties (vanniktech reads the
// same keys for the published coordinates).
subprojects {
    if (name in publishedModules || name == bomModule || path in relocatedArtifacts.keys.map { ":relocations:$it" }) {
        group = providers.gradleProperty("GROUP").get()
        version = providers.gradleProperty("VERSION_NAME").get()
    }
}

// The BOM constrains every published library, so consumers can declare
// platform("io.github.kashif-mehmood-km:camerak-bom:<version>") and leave the versions off the rest.
project(":$bomModule") {
    plugins.withId("java-platform") {
        dependencies {
            constraints {
                publishedModules.forEach { add("api", project(":$it")) }
            }
        }
    }
}
// Each relocation project publishes a POM and nothing else: its <relocation> names the 2.0 coordinate, and
// no Gradle module metadata is published beside it, because Gradle would read that instead of the POM.
relocatedArtifacts.forEach { (oldId, module) ->
    val newId = pomArtifactId(module)
    relocationProject(oldId)?.run {
        plugins.withId("maven-publish") {
            extensions.configure<PublishingExtension> {
                publications.withType<MavenPublication>().configureEach {
                    pom {
                        distributionManagement {
                            relocation {
                                groupId.set(providers.gradleProperty("GROUP"))
                                artifactId.set(newId)
                                version.set(providers.gradleProperty("VERSION_NAME"))
                                message.set(
                                    providers.gradleProperty("GROUP").map { "$oldId is now $it:$newId in CameraK 2.0" },
                                )
                            }
                        }
                    }
                }
            }
        }
        tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }
    }
}

// The publish step of scripts/verify.sh --full: every published module, the BOM and the relocation
// projects publish, unsigned, to a scratch repository under build/verify, and verify_report.py checks
// that every coordinate in publishedCoordinatesFile is there. Compiling the tests never builds what a
// release uploads (the metadata compilations behind each root artifact), so only publishing shows it.
val verifyPublishRepository = providers.gradleProperty("camerak.verify.publishRepo").orNull
// Gradle names the publish task after the repository: publishAllPublicationsToVerifyScratchRepository.
val verifyPublishRepositoryName = "verifyScratch"
val publishedCoordinatesFile = layout.buildDirectory.file("verify/published-coordinates.json")
val publishingProjects =
    publishedModules.map { ":$it" } + ":$bomModule" + relocatedArtifacts.keys.map { ":relocations:$it" }
if (verifyPublishRepository != null) {
    publishingProjects.forEach { path ->
        project(path).plugins.withId("maven-publish") {
            project(path).extensions.configure<PublishingExtension> {
                repositories.maven {
                    name = verifyPublishRepositoryName
                    url = uri(verifyPublishRepository)
                }
            }
        }
    }
}

// What the publish step must find: each published module's root artifact and one artifact per Kotlin
// target (its name lowercased, as the Kotlin plugin names the variant's artifact id), the BOM's root,
// and each relocation POM with the id it relocates to.
fun expectedPublishedCoordinates(): Map<String, Any> {
    val modules =
        publishedModules.associate { module ->
            val targets =
                project(":$module")
                    .extensions
                    .getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
                    .targets
                    .filterNot { it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.common }
                    .map { it.name.lowercase() }
                    .sorted()
            pomArtifactId(module) to targets
        }
    return mapOf(
        "group" to providers.gradleProperty("GROUP").get(),
        "version" to providers.gradleProperty("VERSION_NAME").get(),
        "modules" to modules,
        "bom" to pomArtifactId(bomModule),
        "relocations" to relocatedArtifacts.mapValues { (_, module) -> pomArtifactId(module) },
    )
}

val allVerifyTargets = listOf("desktop", "android", "ios", "screenshots", "lint", "sample")

fun csvProperty(name: String): List<String>? =
    providers
        .gradleProperty(name)
        .orNull
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)

val verifyModules = csvProperty("camerak.verify.modules") ?: publishedModules
(verifyModules - publishedModules).let { unknown ->
    require(unknown.isEmpty()) { "Unknown module(s) $unknown; expected one of $publishedModules" }
}

// Screenshots exist only for cameraK, and the Sample build depends on every module, so those two
// targets do not apply to a narrower module set. Asking for one anyway is an error rather than a
// verifyAll with nothing to run.
fun targetApplies(target: String): Boolean =
    when (target) {
        "screenshots" -> "cameraK" in verifyModules
        "sample" -> verifyModules.containsAll(libraryModules)
        else -> true
    }

val verifyTargets = csvProperty("camerak.verify.targets") ?: allVerifyTargets.filter(::targetApplies)
val recordScreenshots = providers.gradleProperty("camerak.recordScreenshots").orNull.toBoolean()

(verifyTargets - allVerifyTargets).let { unknown ->
    require(unknown.isEmpty()) { "Unknown target(s) $unknown; expected one of $allVerifyTargets" }
}
verifyTargets.filterNot(::targetApplies).let { inapplicable ->
    require(inapplicable.isEmpty()) {
        "Target(s) $inapplicable do not apply to module(s) $verifyModules: screenshots needs cameraK, " +
            "sample needs every module"
    }
}

// A test task that is UP-TO-DATE or FROM-CACHE leaves old result files behind, and the report
// would count tests that did not run in this invocation. verify.sh sets this so every requested
// test task executes.
if (providers.gradleProperty("camerak.verify.rerunTests").orNull.toBoolean()) {
    subprojects {
        tasks.withType<AbstractTestTask>().configureEach {
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
        }
    }
}

// kotlinx-coroutines-test on a consumer's runtime classpath hijacks Dispatchers.Main (#165).
// This fails the build if it, or any other test library, reaches what a published variant gives
// consumers: the desktop and Android runtime classpaths (debug too, though only release is published)
// and the klibs every iOS target compiles against.
val coroutinesTestModule = Regex("""^org\.jetbrains\.kotlinx:kotlinx-coroutines-test""")
val testOnlyModules =
    listOf(
        coroutinesTestModule,
        Regex("""^org\.jetbrains\.kotlin:kotlin-test"""),
        Regex("""^junit:junit$"""),
        Regex("""^org\.junit(\.[^:]+)?:"""),
        Regex("""^org\.robolectric:"""),
        Regex("""^androidx\.test(\.[^:]+)?:"""),
        Regex("""^app\.cash\.paparazzi:"""),
    )
val publishedGraphs =
    listOf(
        "desktopRuntimeClasspath",
        "releaseRuntimeClasspath",
        "debugRuntimeClasspath",
        "iosArm64CompileKlibraries",
        "iosSimulatorArm64CompileKlibraries",
    )
subprojects {
    if (name !in publishedModules) return@subprojects
    val moduleName = name
    val components =
        publishedGraphs.associateWith { configurationName ->
            provider {
                configurations
                    .getByName(configurationName)
                    .incoming.resolutionResult.allComponents
                    .mapNotNull { it.moduleVersion }
                    .map { "${it.group}:${it.name}" }
            }
        }
    tasks.register("checkTestOnlyDependencies") {
        group = "verification"
        description = "Fails if a test library is on a classpath a published variant hands to consumers"
        doLast {
            val leaks = testOnlyLeaks(moduleName, components.mapValues { (_, graph) -> graph.get() })
            check(leaks.isEmpty()) { "Test-only libraries on a published classpath:\n" + leaks.joinToString("\n") }
        }
    }
}

// What a published library hands consumers. Desktop apps get the bytedeco Java bindings a library
// imports and choose their own natives: a *-platform umbrella or a native classifier jar would ship
// every OS's binaries to every app. ocrPlugin alone imports Tesseract and Leptonica.
val nativeClassifierJar = Regex("""-(linux|macosx|windows|android|ios)-[a-z0-9_]+\.jar$""")
val coreBytedecoBindings = setOf("javacv", "javacpp", "ffmpeg", "videoinput")
val bytedecoBindings =
    publishedModules.associateWith {
        if (it == "ocrPlugin") coreBytedecoBindings + setOf("tesseract", "leptonica") else coreBytedecoBindings
    }

// Consumers compile against what a library declares api. Core's public signatures expose javacv's
// FrameGrabber, whose Frame is built on javacpp; no public signature exposes FFmpeg or videoInput.
// camerak and camerak-testing reach javacv only through api(project(":cameraK-core")).
val exportedBytedeco = mapOf("cameraK-core" to setOf("javacv", "javacpp"))

// The artifacts camerak-core may not hand consumers: Compose, and the AndroidX libraries that exist
// for it. Compose UI lives in camerak.
val composeArtifacts =
    listOf(
        Regex("""^androidx\.compose\."""),
        Regex("""^org\.jetbrains\.compose\."""),
        Regex("""^org\.jetbrains\.skiko:"""),
        Regex("""^androidx\.activity:activity-compose$"""),
        Regex("""^(org\.jetbrains\.)?androidx\.lifecycle:lifecycle-[a-z-]*compose"""),
        Regex("""^androidx\.camera:camera-compose$"""),
        Regex("""^androidx\.camera\.viewfinder:viewfinder-compose$"""),
    )

data class ClasspathRule(
    val module: String,
    val configurations: List<String>,
    val forbidden: Regex,
    val reason: String,
)

// The rules 1.x held cameraK to. camerak's graph contains camerak-core's, and it may not add these either.
fun coreClasspathRules(module: String) =
    listOf(
        ClasspathRule(module, publishedGraphs, Regex("""^io\.coil-kt"""), "core imports no Coil"),
        ClasspathRule(module, publishedGraphs, Regex("""^io\.ktor:"""), "core imports no Ktor"),
        ClasspathRule(
            module,
            publishedGraphs,
            Regex("""^com\.google\.zxing:"""),
            "core imports no ZXing; qrScannerPlugin declares the ZXing it uses",
        ),
        ClasspathRule(
            module,
            publishedGraphs,
            Regex("""^androidx\.camera:camera-extensions$"""),
            "core imports no androidx.camera.extensions",
        ),
        ClasspathRule(
            module,
            publishedGraphs,
            Regex("""^co\.touchlab:kermit"""),
            "CameraK logs through CameraKLogger",
        ),
    )

val forbiddenOnPublishedClasspath =
    listOf(
        ClasspathRule(
            "analyzerPlugin",
            listOf("releaseRuntimeClasspath"),
            Regex("""^com\.google\.mlkit:"""),
            "analyzerPlugin imports no ML Kit",
        ),
        ClasspathRule(
            "qrScannerPlugin",
            listOf("desktopRuntimeClasspath"),
            Regex("""^com\.google\.zxing:javase$"""),
            "the desktop scanner reads luminance through zxing core; javase is test-only",
        ),
    ) + coreClasspathRules("cameraK-core") + coreClasspathRules("cameraK") +
        composeArtifacts.map {
            ClasspathRule("cameraK-core", publishedGraphs, it, "camerak-core is Compose-free; Compose UI is in camerak")
        } +
        // camerak-testing exports kotlinx-coroutines-test, which takes over Dispatchers.Main in any app
        // whose runtime classpath it reaches (#165), so no other published module may depend on it.
        (publishedModules - testFixtureModules).map {
            ClasspathRule(
                it,
                publishedGraphs,
                Regex("""^io\.github\.kashif-mehmood-km:camerak-testing$""", RegexOption.IGNORE_CASE),
                "camerak-testing is for test source sets only",
            )
        }

// Consumers compile against every dependency a library declares api, so api is only for libraries
// whose types its public signatures expose. A rule names a library (null for every library) and a
// dependency that must not be on its JVM or Android *ApiElements configurations. Only there does the
// scope matter: the Kotlin Gradle plugin puts implementation dependencies on the Kotlin/Native and
// metadata ApiElements too, because a klib's dependencies are needed to compile against it.
data class ApiExportRule(
    val module: String?,
    val forbidden: Regex,
    val reason: String,
)

val jvmApiElements = setOf("desktopApiElements", "releaseApiElements", "debugApiElements")

val forbiddenApiExports =
    listOf(
        ApiExportRule(
            "cameraK",
            Regex("""^androidx\.activity:activity-compose$"""),
            "only camerak's 1.x permission launcher uses activity-compose",
        ),
        ApiExportRule(
            "cameraK-core",
            Regex("""^androidx\.startup:startup-runtime$"""),
            "CameraK uses no androidx.startup; apps that need it declare it",
        ),
        ApiExportRule(
            "cameraK",
            Regex("""^androidx\.startup:startup-runtime$"""),
            "CameraK uses no androidx.startup; apps that need it declare it",
        ),
        ApiExportRule(
            null,
            Regex("""^org\.jetbrains\.kotlinx:atomicfu$"""),
            "concurrency utilities are never api and no public signature exposes an atomicfu type",
        ),
    )

subprojects {
    if (name !in publishedModules) return@subprojects
    val moduleName = name
    val components =
        publishedGraphs.associateWith { configurationName ->
            provider {
                configurations
                    .getByName(configurationName)
                    .incoming.resolutionResult.allComponents
                    .mapNotNull { it.moduleVersion }
                    .map { "${it.group}:${it.name}" }
            }
        }
    val desktopArtifacts =
        provider {
            configurations
                .getByName("desktopRuntimeClasspath")
                .incoming
                .artifactView { componentFilter { it is ModuleComponentIdentifier } }
                .artifacts.artifacts
                .map { it.file }
        }
    val desktopApiBytedeco =
        provider {
            configurations
                .getByName("desktopApiElements")
                .allDependencies
                .filter { it.group == "org.bytedeco" }
                .map { it.name }
        }
    val apiExports =
        provider {
            configurations
                .filter { it.isCanBeConsumed && it.name in jvmApiElements }
                .associate { configuration ->
                    configuration.name to configuration.allDependencies.map { "${it.group}:${it.name}" }
                }
        }
    tasks.register("checkPublishedClasspath") {
        group = "verification"
        description = "Fails if this library hands consumers natives, umbrellas or libraries it does not import"
        doLast {
            val artifacts = desktopArtifacts.get()
            val problems =
                publishedClasspathProblems(
                    moduleName,
                    components.mapValues { (_, graph) -> graph.get() },
                    apiExports.get(),
                    desktopApiBytedeco.get(),
                    artifacts.map { it.name },
                )
            logger.lifecycle(
                "$path desktopRuntimeClasspath: ${artifacts.size} external jars, " +
                    "%.1f MB".format(artifacts.sumOf { it.length() } / 1_048_576.0),
            )
            check(problems.isEmpty()) {
                "$path hands consumers what it does not need:\n" + problems.distinct().joinToString("\n")
            }
        }
    }
}

// ─── Build-gate rules ───────────────────────────────────────────────────────────────────────────
// checkTestOnlyDependencies and checkPublishedClasspath decide through these two pure functions, so
// checkBuildGateRules can run the same decisions against fixture classpaths in the lint target.

// The test-only libraries on a module's published classpaths (configuration name to "group:name"),
// as "configuration -> group:name".
fun testOnlyLeaks(
    module: String,
    componentsByConfiguration: Map<String, List<String>>,
): List<String> {
    // testingModuleMayExportCoroutinesTest: camerak-testing exists to hand test source sets the coroutine
    // test helpers, so it exports kotlinx-coroutines-test. Only that library, and only that module; the
    // reverse rule in forbiddenOnPublishedClasspath keeps camerak-testing off every other published graph.
    val testingModuleMayExportCoroutinesTest = module in testFixtureModules
    return componentsByConfiguration.flatMap { (configurationName, components) ->
        components
            .filter { id -> testOnlyModules.any { it.containsMatchIn(id) } }
            .filterNot { testingModuleMayExportCoroutinesTest && coroutinesTestModule.containsMatchIn(it) }
            .map { "$configurationName -> $it" }
    }
}

// What a published module hands consumers that it should not: its published classpaths (configuration
// name to "group:name"), what its JVM and Android *ApiElements declare (configuration name to
// "group:name"), the bytedeco artifacts its desktopApiElements declares, and the file names of the
// external jars on its desktopRuntimeClasspath.
fun publishedClasspathProblems(
    module: String,
    componentsByConfiguration: Map<String, List<String>>,
    apiExports: Map<String, List<String>>,
    desktopApiBytedeco: List<String>,
    desktopJarNames: List<String>,
): List<String> {
    val problems = mutableListOf<String>()
    val allowed = bytedecoBindings.getValue(module)
    componentsByConfiguration["desktopRuntimeClasspath"].orEmpty().forEach { component ->
        val (group, artifact) = component.split(':')
        if (group == "org.bytedeco" && artifact !in allowed) {
            problems +=
                "desktopRuntimeClasspath -> $component: $module imports only bytedeco ${allowed.sorted()}" +
                if (artifact.endsWith("-platform")) "; a -platform umbrella ships every OS's natives" else ""
        }
    }
    val exported = exportedBytedeco[module].orEmpty()
    desktopApiBytedeco.filter { it !in exported }.forEach {
        problems +=
            "desktopApiElements -> org.bytedeco:$it: no public $module signature exposes $it types; " +
            "declare it implementation"
    }
    desktopJarNames.filter(nativeClassifierJar::containsMatchIn).forEach {
        problems += "desktopRuntimeClasspath ships the native jar $it; the app chooses its natives"
    }
    forbiddenApiExports.filter { it.module == null || it.module == module }.forEach { rule ->
        apiExports.forEach { (configurationName, dependencies) ->
            dependencies.filter(rule.forbidden::containsMatchIn).forEach {
                problems += "$configurationName exports $it: ${rule.reason}; declare it implementation"
            }
        }
    }
    forbiddenOnPublishedClasspath.filter { it.module == module }.forEach { rule ->
        rule.configurations.forEach { configurationName ->
            componentsByConfiguration[configurationName].orEmpty().filter(rule.forbidden::containsMatchIn).forEach {
                problems += "$configurationName -> $it: ${rule.reason}"
            }
        }
    }
    return problems.distinct()
}

// The iOS deployment targets an Xcode project's text sets below iOS [minimumMajor], one line each; a
// project that sets none is reported too, since then nothing holds the app at the minimum. CameraK's
// engine needs iOS 16 (design §2.4): a Kotlin/Native library cannot raise its consumer's target, so the
// Sample's project keeps it.
fun iosDeploymentTargetsBelow(
    pbxproj: String,
    minimumMajor: Int,
): List<String> {
    val targets = Regex("""IPHONEOS_DEPLOYMENT_TARGET\s*=\s*"?(\d+)((?:\.\d+)*)"?\s*;""").findAll(pbxproj).toList()
    if (targets.isEmpty()) return listOf("sets no IPHONEOS_DEPLOYMENT_TARGET, so nothing holds it at iOS $minimumMajor")
    return targets
        .filter { it.groupValues[1].toInt() < minimumMajor }
        .map { "IPHONEOS_DEPLOYMENT_TARGET ${it.groupValues[1]}${it.groupValues[2]} is below iOS $minimumMajor" }
}

// What is wrong with how a version catalog's text pins camera-compose: CameraXViewfinder reads CameraX's
// SurfaceRequest internals, so it must come from the same release as camera-core, through the same ref.
fun cameraComposeVersionProblems(catalog: String): List<String> {
    val libraries =
        catalog
            .substringAfter("[libraries]", "")
            .substringBefore("\n[")
            .lines()

    fun entry(artifact: String) =
        libraries.firstOrNull {
            "\"androidx.camera:$artifact\"" in it ||
                ("\"androidx.camera\"" in it && Regex("""\bname\s*=\s*"$artifact"""").containsMatchIn(it))
        }

    fun versionRef(line: String) = Regex("""\bversion\.ref\s*=\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
    val core = entry("camera-core") ?: return listOf("declares no androidx.camera:camera-core library")
    val coreRef = versionRef(core) ?: return listOf("camera-core has no version.ref for camera-compose to share")
    val compose = entry("camera-compose") ?: return listOf("declares no androidx.camera:camera-compose library")
    return when (val composeRef = versionRef(compose)) {
        coreRef -> emptyList()
        null -> listOf("camera-compose has no version.ref; use \"$coreRef\", camera-core's")
        else -> listOf("camera-compose uses version.ref \"$composeRef\", not camera-core's \"$coreRef\"")
    }
}

// The types camerak-core no longer annotates for Compose; cameraK/compose-stability.conf lists them.
// VideoCaptureResult left with C1': it is an ERROR-level hint nothing produces.
val strippedStableTypes =
    listOf(
        "com.kashif.cameraK.capabilities.LensInfo",
        "com.kashif.cameraK.capabilities.CameraCapabilities",
        "com.kashif.cameraK.state.CameraKState",
        "com.kashif.cameraK.state.CameraKState.*",
        "com.kashif.cameraK.state.CameraUIState",
        "com.kashif.cameraK.state.CameraKEvent",
        "com.kashif.cameraK.state.CameraKEvent.*",
        "com.kashif.cameraK.state.CameraConfiguration",
        "com.kashif.cameraK.state.CameraKStateHolder",
        "com.kashif.cameraK.video.VideoConfiguration",
    )

// The 2.0 value types UI' adds to cameraK/compose-stability.conf (design §10.3); core never annotated them.
val addedStableTypes =
    listOf(
        "com.kashif.cameraK.state.ZoomState",
        "com.kashif.cameraK.recording.RecordingState",
        "com.kashif.cameraK.recording.RecordingState.*",
        "com.kashif.cameraK.error.CameraKError",
        "com.kashif.cameraK.error.CameraKError.*",
        "com.kashif.cameraK.capture.CapturedImage",
        "com.kashif.cameraK.capture.RecordedVideo",
        "com.kashif.cameraK.result.CameraKResult",
        "com.kashif.cameraK.result.CameraKResult.*",
    )

// What is wrong with cameraK/compose-stability.conf's [confLines] given the Kotlin types the published
// modules' main sources declare ([declaredTypes]: fully qualified name to its @Deprecated level, null
// when not deprecated). Every entry must name a declared type (for "X.*", the type X) that is not
// deprecated at ERROR or HIDDEN: a stability entry for a removed or hint-only type is dead weight.
fun stabilityConfigProblems(
    confLines: List<String>,
    declaredTypes: Map<String, DeprecationLevel?>,
): List<String> =
    confLines
        .map { it.substringBefore("//").trim() }
        .filter(String::isNotEmpty)
        .mapNotNull { entry ->
            val type = entry.removeSuffix(".*")
            val level = declaredTypes[type]
            when {
                type !in declaredTypes -> "$entry names $type, which no published module declares"
                level == DeprecationLevel.ERROR || level == DeprecationLevel.HIDDEN ->
                    "$entry names $type, deprecated at $level"
                else -> null
            }
        }

// What is wrong with the relocation POMs of the renamed artifacts. [expected] maps each old id to its new
// id, [poms] each old id to the POM text its relocation project generates (null when there is none), and
// [moduleMetadata] holds the old ids whose Gradle module metadata is still generated, which Gradle would
// read instead of the POM and so never see the relocation.
fun relocationPomProblems(
    expected: Map<String, String>,
    group: String,
    version: String,
    poms: Map<String, String?>,
    moduleMetadata: Set<String>,
): List<String> =
    expected.flatMap { (oldId, newId) ->
        val text = poms[oldId] ?: return@flatMap listOf("$oldId publishes no relocation POM")
        val pom =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(org.xml.sax.InputSource(java.io.StringReader(text)))
                .documentElement
        val relocation =
            pom
                .getElementsByTagName("distributionManagement")
                .let { if (it.length == 0) null else it.item(0) as org.w3c.dom.Element }
                ?.let { pomField(it, "relocation") }
                ?.takeUnless { it == "<missing>" }
        val target = "{groupId=$group, artifactId=$newId, version=$version}"
        listOfNotNull(
            "$oldId relocates to itself; its successor must have a new id".takeIf { newId == oldId },
            "$oldId: POM packaging is ${pomField(pom, "packaging")}, not pom"
                .takeIf { pomField(pom, "packaging") != "pom" },
            "$oldId: POM has no <relocation>".takeIf { relocation == null },
            "$oldId: POM relocates to $relocation, not $target".takeIf {
                relocation != null &&
                    relocation.removeSurrounding("{", "}").split(", ").let { fields ->
                        "groupId=$group" !in fields || "artifactId=$newId" !in fields || "version=$version" !in fields
                    }
            },
            "$oldId: Gradle module metadata is published beside the relocation POM, so Gradle never reads it"
                .takeIf { oldId in moduleMetadata },
        ) +
            listOf("name", "description", "url", "licenses", "developers", "scm")
                .filter { pomField(pom, it) == "<missing>" || pomField(pom, it).isBlank() }
                .map { "$oldId: POM has no <$it>, which Maven Central requires" }
    }

// The class, interface, object and typealias declarations in one Kotlin [source], by fully qualified
// name (a nested declaration under its enclosing one, by indentation), with their @Deprecated level.
fun kotlinTypeDeclarations(source: String): Map<String, DeprecationLevel?> {
    val packageName = Regex("""(?m)^package\s+([\w.]+)""").find(source)?.groupValues?.get(1)
    val declaration =
        Regex(
            """^(\s*)(?:@\S+\s+)*(?:(?:public|internal|private|protected|expect|actual|data|sealed|enum|""" +
                """annotation|value|inline|abstract|open|final|fun|inner)\s+)*""" +
                """(class|interface|object|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)""",
        )
    val declared = linkedMapOf<String, DeprecationLevel?>()
    val enclosing = ArrayDeque<Pair<Int, String>>()
    val annotations = StringBuilder()
    var openParens = 0
    source.lines().forEach { line ->
        val trimmed = line.trim()
        val match = declaration.find(line)
        when {
            trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") -> Unit
            match != null && openParens == 0 -> {
                val indent = match.groupValues[1].length
                while (enclosing.isNotEmpty() && enclosing.last().first >= indent) enclosing.removeLast()
                val name =
                    (listOfNotNull(packageName) + enclosing.map { it.second } + match.groupValues[3])
                        .joinToString(".")
                val text = annotations.toString() + line
                declared[name] =
                    when {
                        "@Deprecated" !in text -> null
                        "DeprecationLevel.HIDDEN" in text -> DeprecationLevel.HIDDEN
                        "DeprecationLevel.ERROR" in text -> DeprecationLevel.ERROR
                        else -> DeprecationLevel.WARNING
                    }
                enclosing.addLast(indent to match.groupValues[3])
                annotations.clear()
            }
            trimmed.startsWith("@") || openParens > 0 -> {
                annotations.append(line).append('\n')
                openParens += line.count { it == '(' } - line.count { it == ')' }
            }
            else -> annotations.clear()
        }
    }
    return declared
}

// ─── Published API rules (design §4, §10.3, §12.4 Z1) ───────────────────────────────────────────

// One declaration a published module's main source makes public. Explicit API mode makes every public
// declaration say `public`, so a declaration counts when it carries that modifier and nothing around it
// is private or internal. [header] is its text up to its body or initializer, parameters included;
// [kdoc] the KDoc above it; [deprecation] the strongest @Deprecated level on it or on what encloses it.
data class PublicDeclaration(
    val location: String,
    val header: String,
    val kdoc: String,
    val deprecation: DeprecationLevel?,
)

val declarationStart =
    Regex(
        """^((?:(?:public|internal|private|protected|expect|actual|data|sealed|enum|annotation|value|inline|""" +
            """abstract|open|final|override|inner|const|lateinit|suspend|operator|infix|tailrec|external|""" +
            """companion|fun(?=\s+interface))\s+)*)(class|interface|object|typealias|fun|val|var|constructor)\b""",
    )

fun parenBalance(text: String): Int = text.count { it == '(' } - text.count { it == ')' }

fun deprecationIn(text: String): DeprecationLevel? =
    when {
        "@Deprecated" !in text -> null
        "DeprecationLevel.HIDDEN" in text -> DeprecationLevel.HIDDEN
        "DeprecationLevel.ERROR" in text -> DeprecationLevel.ERROR
        else -> DeprecationLevel.WARNING
    }

// [line] without its leading annotations, and the annotations' text; null when an annotation's
// arguments run on past the line.
fun splitAnnotations(line: String): Pair<String, String>? {
    var at = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    while (at < line.length && line[at] == '@') {
        at++
        while (at < line.length && (line[at].isLetterOrDigit() || line[at] in "_.:")) at++
        if (at < line.length && line[at] == '(') {
            var depth = 0
            while (at < line.length) {
                if (line[at] == '(') depth++
                if (line[at] == ')') depth--
                at++
                if (depth == 0) break
            }
            if (depth != 0) return null
        }
        while (at < line.length && line[at].isWhitespace()) at++
    }
    return line.substring(0, at) to line.substring(at)
}

// The declaration's text from [start] to its body ('{') or initializer ('='), whichever comes first
// outside parentheses; a typealias keeps what it aliases.
fun declarationHeader(
    lines: List<String>,
    start: Int,
    first: String,
    keepInitializer: Boolean,
): String {
    val text = StringBuilder(first)
    var depth = parenBalance(first)
    var next = start + 1
    while (depth > 0 && next < lines.size) {
        text.append('\n').append(lines[next])
        depth += parenBalance(lines[next])
        next++
    }
    var level = 0
    text.forEachIndexed { index, char ->
        when (char) {
            '(' -> level++
            ')' -> level--
            '{' -> if (level == 0) return text.substring(0, index)
            '=' ->
                if (level == 0 &&
                    !keepInitializer &&
                    text.getOrNull(index + 1) != '=' &&
                    text.getOrNull(index - 1) !in listOf('=', '!', '<', '>')
                ) {
                    return text.substring(0, index)
                }
        }
    }
    return text.toString()
}

fun publicDeclarations(
    path: String,
    source: String,
): List<PublicDeclaration> {
    val lines = source.lines()
    val found = mutableListOf<PublicDeclaration>()
    // indent, private or internal, deprecation
    val enclosing = ArrayDeque<Triple<Int, Boolean, DeprecationLevel?>>()
    val kdoc = StringBuilder()
    var inKdoc = false
    val annotations = StringBuilder()
    var annotationParens = 0
    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()
        when {
            inKdoc -> {
                kdoc.append(line).append('\n')
                if ("*/" in trimmed) inKdoc = false
            }
            trimmed.startsWith("/**") -> {
                kdoc.clear().append(line).append('\n')
                inKdoc = "*/" !in trimmed.removePrefix("/**")
                annotations.clear()
            }
            trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") -> Unit
            annotationParens > 0 -> {
                annotations.append(line).append('\n')
                annotationParens += parenBalance(line)
            }
            else -> {
                val split = splitAnnotations(line)
                val match = split?.let { declarationStart.find(it.second) }
                when {
                    split == null || (match == null && split.second.isBlank()) -> {
                        annotations.append(line).append('\n')
                        annotationParens += parenBalance(line).coerceAtLeast(0)
                    }
                    match == null -> {
                        annotations.clear()
                        kdoc.clear()
                    }
                    else -> {
                        val indent = line.indexOfFirst { !it.isWhitespace() }
                        while (enclosing.isNotEmpty() && enclosing.last().first >= indent) enclosing.removeLast()
                        val modifiers = match.groupValues[1].trim().split(Regex("""\s+"""))
                        val keyword = match.groupValues[2]
                        val deprecation =
                            (enclosing.map { it.third } + deprecationIn(annotations.toString() + split.first))
                                .filterNotNull()
                                .maxByOrNull { it.ordinal }
                        val hidden =
                            modifiers.any { it == "private" || it == "internal" } || enclosing.any { it.second }
                        if (!hidden && "public" in modifiers) {
                            val header = declarationHeader(lines, index, split.second, keyword == "typealias")
                            found +=
                                PublicDeclaration("$path:${index + 1}", header.trim(), kdoc.toString(), deprecation)
                        }
                        if (keyword in setOf("class", "interface", "object")) {
                            enclosing.addLast(Triple(indent, hidden, deprecation))
                        }
                        annotations.clear()
                        kdoc.clear()
                    }
                }
            }
        }
    }
    return found
}

fun PublicDeclaration.isLive(): Boolean =
    deprecation != DeprecationLevel.ERROR && deprecation != DeprecationLevel.HIDDEN

fun PublicDeclaration.describe(): String = "$location: ${header.lines().first().trim()}"

fun headerProblems(
    declarations: List<PublicDeclaration>,
    pattern: Regex,
    reason: String,
): List<String> =
    declarations
        .filter { it.isLive() && pattern.containsMatchIn(it.header) }
        .map { "${it.describe()}: $reason" }

// public_data_class_fails_lint: a public data class cannot grow without breaking binaries (CC-28).
fun publicDataClassProblems(declarations: List<PublicDeclaration>): List<String> =
    headerProblems(
        declarations,
        Regex("""\bdata\s+class\b"""),
        "a public data class; its copy and componentN change signature with every new property",
    )

// public_channel_fails_lint: a Channel in a public signature hands callers the library's own queue.
fun publicChannelProblems(declarations: List<PublicDeclaration>): List<String> =
    headerProblems(
        declarations,
        Regex("""\b(?:Send|Receive)?Channel\s*<"""),
        "a Channel in a public signature; expose a Flow",
    )

// public_mutable_flow_fails_lint: a Mutable*Flow in a public signature lets callers write library state.
fun publicMutableFlowProblems(declarations: List<PublicDeclaration>): List<String> =
    headerProblems(
        declarations,
        Regex("""\bMutable(?:State|Shared)Flow\b"""),
        "a mutable flow in a public signature; expose a StateFlow or SharedFlow",
    )

// The first words of a KDoc, without its comment markers.
fun kdocText(kdoc: String): String =
    kdoc
        .lines()
        .joinToString(" ") {
            it
                .trim()
                .removePrefix("/**")
                .removeSuffix("*/")
                .removePrefix("*")
                .trim()
        }.trim()

// public_sealed_or_enum_without_classification_fails_lint: every public sealed type and enum states in its
// KDoc's first words whether it is closed or open (Decision 8, design §4). An actual declaration's KDoc is
// on its expect.
fun unclassifiedHierarchyProblems(declarations: List<PublicDeclaration>): List<String> =
    declarations
        .filter { declaration ->
            declaration.isLive() &&
                Regex("""\b(?:sealed\s+(?:class|interface)|enum\s+class)\b""").containsMatchIn(declaration.header) &&
                !Regex("""\bactual\b""").containsMatchIn(declaration.header) &&
                kdocText(declaration.kdoc).let { !it.startsWith("Closed") && !it.startsWith("Open") }
        }.map { "${it.describe()}: its KDoc does not open with \"Closed\" or \"Open\" (design §4)" }

// unreviewed_abi_change_fails: every published module validates its ABI against committed dumps, and its
// check task is in the lint group, so verify.sh fails on any ABI change the dumps do not record.
// [enabled] maps a module to whether ABI validation is on; [checkTaskGroups] to its check task's group,
// null when it has no check task.
fun abiValidationProblems(
    modules: List<String>,
    enabled: Map<String, Boolean>,
    checkTaskGroups: Map<String, String?>,
): List<String> =
    modules.flatMap { module ->
        val group = checkTaskGroups[module]
        listOfNotNull(
            "$module does not validate its ABI".takeIf { enabled[module] != true },
            "$module has no $abiCheckTask task".takeIf {
                enabled[module] == true &&
                    !checkTaskGroups.containsKey(
                        module,
                    )
            },
            "$module's $abiCheckTask is not in the '$lintGroup' group, so verify.sh never runs it"
                .takeIf { enabled[module] == true && group != lintGroup },
        )
    }

// The public declarations of every published module's main sources (src/*Main).
fun publishedApiDeclarations(moduleDirs: List<File>): List<PublicDeclaration> =
    moduleDirs
        .flatMap { module ->
            module
                .resolve("src")
                .listFiles()
                .orEmpty()
                .filter { it.name.endsWith("Main") }
        }.flatMap { sourceSet -> sourceSet.walkTopDown().filter { it.isFile && it.extension == "kt" } }
        .flatMap { publicDeclarations(it.relativeTo(rootDir).invariantSeparatorsPath, it.readText()) }

// One API rule's case: it rejects the [planted] source, accepts the [kept] one, and finds nothing in [sources].
fun apiRuleCase(
    rule: (List<PublicDeclaration>) -> List<String>,
    planted: String,
    kept: String,
    sources: List<PublicDeclaration>,
): List<String> {
    val accepted = rule(publicDeclarations("Kept.kt", "package fixture\n\n$kept\n"))
    // The hint's @Deprecated goes on the declaration itself, below any KDoc.
    val hint =
        "package fixture\n\n" +
            planted
                .replace("Planted", "Hint")
                .replaceFirst("public ", "@Deprecated(\"Removed\", level = DeprecationLevel.ERROR)\npublic ") + "\n"
    return listOfNotNull(
        "the planted source is accepted:\n$planted"
            .takeIf { rule(publicDeclarations("Planted.kt", "package fixture\n\n$planted\n")).isEmpty() },
        "the kept source is rejected: $accepted".takeIf { accepted.isNotEmpty() },
        "an ERROR-level hint is rejected".takeIf { rule(publicDeclarations("Hint.kt", hint)).isNotEmpty() },
        "the published sources declare nothing public".takeIf { sources.isEmpty() },
    ) + rule(sources)
}

// The Kotlin plugin's ABI check task, which compares each target's ABI with the dumps under api/. The
// plugin creates it only in a module that calls abiValidation(), so it existing means validation is on.
val abiCheckTask = "checkKotlinAbi"

// abi_dump_names_no_atomicfu_type: no committed ABI dump (JVM .api or klib .klib.api; dump path to its
// text) names an atomicfu type. atomicfu is an implementation detail with no compiler plugin applied, so
// one of its types in a public signature would tie consumers to the library (§14 row 2).
fun atomicfuDumpProblems(dumps: Map<String, String>): List<String> =
    dumps.flatMap { (path, text) ->
        text.lines().mapIndexedNotNull { index, line ->
            "$path:${index + 1}: ${line.trim()}".takeIf { "kotlinx.atomicfu" in line || "kotlinx/atomicfu" in line }
        }
    }

// camerax_api_scope_matches_abi_dump: camerak-core hands consumers, as api, exactly the CameraX artifacts
// whose types its Android ABI dump names (§14 row 4). [apiArtifacts] are the "group:name" dependencies its
// androidMain declares api; an artifact androidx.camera:camera-X owns the package androidx.camera.X.
fun cameraxApiScopeProblems(
    apiArtifacts: List<String>,
    dump: String,
): List<String> {
    val named = Regex("""androidx[/.]camera[/.]([a-z0-9]+)[/.]""").findAll(dump).map { it.groupValues[1] }.toSet()
    val api =
        apiArtifacts
            .filter { it.startsWith("androidx.camera:") }
            .associateBy { it.substringAfter(':').removePrefix("camera-") }
    return api
        .filterKeys { it !in named }
        .map { (_, artifact) ->
            "$artifact is api, but no public signature names its types; declare it implementation"
        } +
        (named - api.keys).map { "the ABI dump names androidx.camera.$it, but androidx.camera:camera-$it is not api" }
}

// The gate rules run against fixtures, and the gates checked to cover the modules the 2.0 split adds.
tasks.register("checkBuildGateRules") {
    group = lintGroup
    description =
        "Runs the published-classpath and test-only rules against fixtures and checks the gates cover every module"
    val splitModules = listOf("cameraK-core", "cameraK-testing")
    val moduleTasks =
        provider {
            splitModules.associateWith { name ->
                findProject(":$name")
                    ?.tasks
                    ?.names
                    ?.toSet()
                    .orEmpty()
            }
        }
    val bomConstraints =
        provider {
            findProject(":$bomModule")
                ?.configurations
                ?.findByName("api")
                ?.dependencyConstraints
                ?.map { it.name }
                .orEmpty()
        }
    val registeredStabilityFiles =
        provider {
            findProject(":cameraK")
                ?.extensions
                ?.findByType<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension>()
                ?.stabilityConfigurationFiles
                ?.get()
                ?.map { it.asFile.canonicalFile }
                .orEmpty()
        }
    val apiSourceDirs = publishedModules.map { layout.projectDirectory.dir(it).asFile }
    val abiDumpDirs = publishedModules.associateWith { layout.projectDirectory.dir("$it/api").asFile }
    val coreAndroidDump = layout.projectDirectory.file("cameraK-core/api/android/cameraK-core.api").asFile
    val coreAndroidApi =
        provider {
            project(":cameraK-core")
                .configurations
                .getByName("androidMainApi")
                .dependencies
                .map { "${it.group}:${it.name}" }
        }
    val abiValidation =
        provider {
            publishedModules.associateWith { abiCheckTask in project(":$it").tasks.names } to
                publishedModules.associateWith { project(":$it").tasks.findByName(abiCheckTask)?.group }
        }
    val stabilityConfig = layout.projectDirectory.file("cameraK/compose-stability.conf").asFile
    val sampleXcodeProject = layout.projectDirectory.file("iosApp/iosApp.xcodeproj/project.pbxproj").asFile
    val versionCatalog = layout.projectDirectory.file("gradle/libs.versions.toml").asFile
    val publishedModuleDirs = libraryModules.map { layout.projectDirectory.dir(it).asFile }
    relocatedArtifacts.keys.mapNotNull(::relocationProject).forEach {
        dependsOn("${it.path}:generatePomFileForMavenPublication")
    }
    val relocationTargets = relocatedArtifacts.mapValues { (_, module) -> pomArtifactId(module) }
    val relocationPoms =
        provider {
            relocatedArtifacts.keys.associateWith { oldId ->
                relocationProject(oldId)
                    ?.layout
                    ?.buildDirectory
                    ?.file("publications/maven/pom-default.xml")
                    ?.get()
                    ?.asFile
                    ?.takeIf { it.isFile }
                    ?.readText()
            }
        }
    val relocationModuleMetadata =
        provider {
            relocatedArtifacts.keys
                .filter { oldId ->
                    relocationProject(oldId)?.tasks?.withType<GenerateModuleMetadata>()?.any { it.enabled } == true
                }.toSet()
        }
    val releaseGroup = providers.gradleProperty("GROUP")
    val releaseVersion = providers.gradleProperty("VERSION_NAME")
    doLast {
        fun graphs(vararg entries: Pair<String, List<String>>) =
            publishedGraphs.associateWith { emptyList<String>() } + entries

        val coroutinesTest = "org.jetbrains.kotlinx:kotlinx-coroutines-test"

        // Each case returns what is wrong with the rule, or nothing.
        val cases: List<Pair<String, () -> List<String>>> =
            listOf(
                "core_graph_with_compose_fails" to {
                    listOf(
                        "org.jetbrains.compose.ui:ui",
                        "androidx.activity:activity-compose",
                        "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose",
                        "androidx.camera:camera-compose",
                        "androidx.camera.viewfinder:viewfinder-compose",
                    ).filter { component ->
                        publishedClasspathProblems(
                            "cameraK-core",
                            graphs("releaseRuntimeClasspath" to listOf(component)),
                            emptyMap(),
                            emptyList(),
                            emptyList(),
                        ).isEmpty()
                    }.map { "cameraK-core with $it on releaseRuntimeClasspath is accepted" }
                },
                "coroutines_test_on_core_graph_fails" to {
                    if (testOnlyLeaks(
                            "cameraK-core",
                            graphs("desktopRuntimeClasspath" to listOf(coroutinesTest)),
                        ).isEmpty()
                    ) {
                        listOf("cameraK-core with kotlinx-coroutines-test on desktopRuntimeClasspath has no leak")
                    } else {
                        emptyList()
                    }
                },
                "camerak_testing_on_plugin_runtime_graph_fails" to {
                    val testing = "io.github.kashif-mehmood-km:cameraK-testing"
                    if (publishedClasspathProblems(
                            "qrScannerPlugin",
                            graphs("desktopRuntimeClasspath" to listOf(testing)),
                            emptyMap(),
                            emptyList(),
                            emptyList(),
                        ).isEmpty()
                    ) {
                        listOf("qrScannerPlugin with $testing on desktopRuntimeClasspath is accepted")
                    } else {
                        emptyList()
                    }
                },
                "camerak_testing_may_export_coroutines_test" to {
                    testOnlyLeaks(
                        "cameraK-testing",
                        publishedGraphs.associateWith { listOf(coroutinesTest, "$coroutinesTest-jvm") },
                    ).map { "cameraK-testing leaks $it" }
                },
                "camerak_testing_with_kotlin_test_fails" to {
                    if (testOnlyLeaks(
                            "cameraK-testing",
                            graphs("desktopRuntimeClasspath" to listOf("org.jetbrains.kotlin:kotlin-test")),
                        ).isEmpty()
                    ) {
                        listOf("cameraK-testing with kotlin-test on desktopRuntimeClasspath has no leak")
                    } else {
                        emptyList()
                    }
                },
                "bytedeco_export_rule_guards_camerak_core" to {
                    fun exporting(
                        module: String,
                        bindings: List<String>,
                    ) = publishedClasspathProblems(module, graphs(), emptyMap(), bindings, emptyList())
                    listOfNotNull(
                        "cameraK-core exporting ffmpeg is accepted".takeIf {
                            exporting(
                                "cameraK-core",
                                listOf("ffmpeg"),
                            ).isEmpty()
                        },
                        exporting("cameraK-core", listOf("javacv", "javacpp"))
                            .takeIf { it.isNotEmpty() }
                            ?.let { "cameraK-core exporting javacv and javacpp is rejected: $it" },
                        "cameraK exporting javacv is accepted".takeIf {
                            exporting(
                                "cameraK",
                                listOf("javacv"),
                            ).isEmpty()
                        },
                    )
                },
                "activity_compose_export_rule_guards_camerak" to {
                    if (publishedClasspathProblems(
                            "cameraK",
                            graphs(),
                            mapOf("releaseApiElements" to listOf("androidx.activity:activity-compose")),
                            emptyList(),
                            emptyList(),
                        ).isEmpty()
                    ) {
                        listOf("cameraK exporting activity-compose on releaseApiElements is accepted")
                    } else {
                        emptyList()
                    }
                },
                "core_graph_is_compose_free" to {
                    composeArtifacts
                        .filterNot { artifact ->
                            forbiddenOnPublishedClasspath.any {
                                it.module == "cameraK-core" &&
                                    it.forbidden.pattern == artifact.pattern &&
                                    it.configurations == publishedGraphs
                            }
                        }.map { "no cameraK-core ClasspathRule on every published graph forbids ${it.pattern}" } +
                        listOfNotNull(
                            "cameraK-core is not in publishedModules".takeIf { "cameraK-core" !in publishedModules },
                            ":cameraK-core:checkPublishedClasspath does not exist"
                                .takeIf { "checkPublishedClasspath" !in moduleTasks.get().getValue("cameraK-core") },
                        )
                },
                "published_gates_cover_camerak_core_and_camerak_testing" to {
                    val full = verifyTaskPaths(allVerifyTargets, publishedModules, false)
                    val constraints = bomConstraints.get()
                    listOfNotNull(
                        "cameraK-core is not in libraryModules".takeIf { "cameraK-core" !in libraryModules },
                    ) +
                        splitModules.flatMap { module ->
                            val tasks = moduleTasks.get().getValue(module)
                            listOfNotNull(
                                "$module is not in publishedModules".takeIf { module !in publishedModules },
                                "the BOM does not constrain $module".takeIf { module !in constraints },
                            ) +
                                listOf("checkTestOnlyDependencies", "checkPublishedClasspath", "checkAndroidSdkFloor")
                                    .filterNot(tasks::contains)
                                    .map { ":$module:$it does not exist" } +
                                listOf("testDebugUnitTest", "desktopTest", "iosSimulatorArm64Test", "lintRelease")
                                    .map { ":$module:$it" }
                                    .filterNot(full::contains)
                                    .map { "scripts/verify.sh --full does not run $it" }
                        }
                },
                "ios_sample_deployment_target_at_least_16" to {
                    fun target(version: String) = "\t\t\t\tIPHONEOS_DEPLOYMENT_TARGET = $version;\n"
                    listOfNotNull(
                        "a project with a 15.0 target is accepted"
                            .takeIf { iosDeploymentTargetsBelow(target("16.2") + target("15.0"), 16).isEmpty() },
                        "a project with no deployment target is accepted"
                            .takeIf { iosDeploymentTargetsBelow("// no build settings\n", 16).isEmpty() },
                        "a project with 16.0 and 17.4 targets is rejected"
                            .takeIf { iosDeploymentTargetsBelow(target("16.0") + target("17.4"), 16).isNotEmpty() },
                    ) +
                        iosDeploymentTargetsBelow(sampleXcodeProject.readText(), 16)
                            .map { "${sampleXcodeProject.relativeTo(rootDir)}: $it" }
                },
                "camera_compose_must_match_camerax_version" to {
                    fun catalog(composeVersion: String?) =
                        "[versions]\ncamerax = \"1.5.3\"\ncamerax-compose = \"1.5.3\"\n[libraries]\n" +
                            "camera-core = { module = \"androidx.camera:camera-core\", version.ref = \"camerax\" }\n" +
                            composeVersion
                                ?.let { "camera-compose = { module = \"androidx.camera:camera-compose\", $it }\n" }
                                .orEmpty()
                    val matching = cameraComposeVersionProblems(catalog("version.ref = \"camerax\""))
                    listOfNotNull(
                        "a catalog with camera-compose at version.ref camerax is rejected: $matching"
                            .takeIf { matching.isNotEmpty() },
                        "a catalog with camera-compose at a literal version is accepted"
                            .takeIf { cameraComposeVersionProblems(catalog("version = \"1.5.3\"")).isEmpty() },
                        "a catalog with camera-compose at its own ref is accepted"
                            .takeIf {
                                cameraComposeVersionProblems(catalog("version.ref = \"camerax-compose\"")).isEmpty()
                            },
                        "a catalog without camera-compose is accepted"
                            .takeIf { cameraComposeVersionProblems(catalog(null)).isEmpty() },
                    ) +
                        cameraComposeVersionProblems(versionCatalog.readText())
                            .map { "${versionCatalog.relativeTo(rootDir)}: $it" }
                },
                "stability_config_lists_no_deleted_type" to {
                    val fixtureTypes =
                        mapOf(
                            "a.Live" to null,
                            "a.Warned" to DeprecationLevel.WARNING,
                            "a.Hint" to DeprecationLevel.ERROR,
                            "a.Hidden" to DeprecationLevel.HIDDEN,
                        )

                    fun judged(vararg lines: String) = stabilityConfigProblems(lines.toList(), fixtureTypes)
                    val declaredTypes =
                        publishedModuleDirs
                            .flatMap { module ->
                                module
                                    .resolve("src")
                                    .listFiles()
                                    .orEmpty()
                                    .filter { it.name.endsWith("Main") }
                            }.flatMap { sourceSet ->
                                sourceSet.walkTopDown().filter { it.isFile && it.extension == "kt" }
                            }.map { kotlinTypeDeclarations(it.readText()) }
                            .fold(emptyMap<String, DeprecationLevel?>()) { all, file -> all + file }
                    listOfNotNull(
                        "a type no module declares is accepted".takeIf { judged("a.Gone").isEmpty() },
                        "an ERROR-deprecated type is accepted".takeIf { judged("a.Hint").isEmpty() },
                        "a wildcard over an ERROR-deprecated type is accepted".takeIf { judged("a.Hint.*").isEmpty() },
                        "a HIDDEN type is accepted".takeIf { judged("a.Hidden").isEmpty() },
                        "live and WARNING-deprecated types are rejected: ${judged(
                            "a.Live",
                            "a.Warned",
                            "// a.Gone",
                            "",
                        )}"
                            .takeIf { judged("a.Live", "a.Warned", "// a.Gone", "").isNotEmpty() },
                        "a wildcard over a live type is rejected".takeIf { judged("a.Live.*").isNotEmpty() },
                        "the published sources declare no type".takeIf { declaredTypes.isEmpty() },
                    ) +
                        stabilityConfigProblems(stabilityConfig.readLines(), declaredTypes)
                            .map { "${stabilityConfig.relativeTo(rootDir)}: $it" }
                },
                "old_artifact_ids_publish_relocation_poms" to {
                    fun relocationTo(
                        artifactId: String,
                        version: String = "2.0",
                        group: String = "g",
                    ) = "<groupId>$group</groupId><artifactId>$artifactId</artifactId><version>$version</version>"

                    fun pom(
                        relocation: String? = relocationTo("new-id"),
                        packaging: String = "pom",
                    ) = listOfNotNull(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">",
                        "<modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>old_id</artifactId>",
                        "<version>2.0</version><packaging>$packaging</packaging><name>old_id</name>",
                        "<description>Relocated to g:new-id</description><url>https://example.org</url>",
                        "<licenses><license><name>L</name></license></licenses>",
                        "<developers><developer><id>d</id></developer></developers>",
                        "<scm><url>https://example.org</url></scm>",
                        relocation?.let { "<distributionManagement><relocation>$it</relocation>" },
                        relocation?.let { "</distributionManagement>" },
                        "</project>",
                    ).joinToString("\n")

                    fun judged(
                        pom: String?,
                        metadata: Set<String> = emptySet(),
                        expected: Map<String, String> = mapOf("old_id" to "new-id"),
                    ) = relocationPomProblems(expected, "g", "2.0", mapOf("old_id" to pom), metadata)
                    val correct = judged(pom())
                    listOfNotNull(
                        "a correct relocation POM is rejected: $correct".takeIf { correct.isNotEmpty() },
                        "a POM with no <relocation> is accepted".takeIf { judged(pom(relocation = null)).isEmpty() },
                        "a POM relocating to the old id is accepted"
                            .takeIf { judged(pom(relocationTo("old_id"))).isEmpty() },
                        "a POM relocating to another version is accepted"
                            .takeIf { judged(pom(relocationTo("new-id", version = "1.2"))).isEmpty() },
                        "a POM relocating to another group is accepted"
                            .takeIf { judged(pom(relocationTo("new-id", group = "h"))).isEmpty() },
                        "a jar-packaged POM is accepted".takeIf { judged(pom(packaging = "jar")).isEmpty() },
                        "a POM with module metadata on is accepted".takeIf { judged(pom(), setOf("old_id")).isEmpty() },
                        "an expected id with no POM is accepted".takeIf { judged(null).isEmpty() },
                        "a POM without its licence is accepted"
                            .takeIf { judged(pom().replace(Regex("<licenses>.*</licenses>"), "")).isEmpty() },
                        "an old id expected to relocate to itself is accepted".takeIf {
                            judged(pom(relocationTo("old_id")), expected = mapOf("old_id" to "old_id")).isEmpty()
                        },
                    ) +
                        listOfNotNull(
                            "relocatedArtifacts relocates ${relocatedArtifacts.keys}, not the four 1.x plugin ids"
                                .takeIf {
                                    relocatedArtifacts.keys !=
                                        setOf("qr_scanner_plugin", "ocr_plugin", "analyzer_plugin") +
                                        "image_saver_plugin"
                                },
                        ) +
                        relocationPomProblems(
                            relocationTargets,
                            releaseGroup.get(),
                            releaseVersion.get(),
                            relocationPoms.get(),
                            relocationModuleMetadata.get(),
                        )
                },
                "public_data_class_fails_lint" to {
                    apiRuleCase(
                        ::publicDataClassProblems,
                        planted = "public data class Planted(public val a: Int)",
                        kept = "public class Kept(public val a: Int)",
                        sources = publishedApiDeclarations(apiSourceDirs),
                    )
                },
                "public_channel_fails_lint" to {
                    apiRuleCase(
                        ::publicChannelProblems,
                        planted =
                            "public class Planted {\n    public fun events(\n        a: Int,\n" +
                                "    ): ReceiveChannel<Int> = TODO()\n}",
                        kept = "public class Kept {\n    private val events = Channel<Int>()\n}",
                        sources = publishedApiDeclarations(apiSourceDirs),
                    )
                },
                "public_mutable_flow_fails_lint" to {
                    apiRuleCase(
                        ::publicMutableFlowProblems,
                        planted = "public class Planted(public val state: MutableStateFlow<Int>)",
                        kept =
                            "public class Kept {\n" +
                                "    internal val state: MutableStateFlow<Int> = MutableStateFlow(0)\n}",
                        sources = publishedApiDeclarations(apiSourceDirs),
                    )
                },
                "public_sealed_or_enum_without_classification_fails_lint" to {
                    apiRuleCase(
                        ::unclassifiedHierarchyProblems,
                        planted = "/** The planted modes. */\npublic enum class Planted { A, B }",
                        kept =
                            "/** Closed: the kept modes. A new one waits for a major release. */\n" +
                                "public enum class Kept { A, B }",
                        sources = publishedApiDeclarations(apiSourceDirs),
                    )
                },
                "unreviewed_abi_change_fails" to {
                    val everyModule = publishedModules.associateWith { true }
                    val inLint = publishedModules.associateWith { lintGroup }
                    val correct = abiValidationProblems(publishedModules, everyModule, inLint)
                    val (enabled, groups) = abiValidation.get()
                    listOfNotNull(
                        "every module validating its ABI in the lint group is rejected: $correct"
                            .takeIf { correct.isNotEmpty() },
                        "a module left out of ABI validation is accepted".takeIf {
                            abiValidationProblems(publishedModules, everyModule + ("cameraK-testing" to false), inLint)
                                .isEmpty()
                        },
                        "a module whose ABI check is outside the lint group is accepted".takeIf {
                            abiValidationProblems(publishedModules, everyModule, inLint + ("cameraK" to "verification"))
                                .isEmpty()
                        },
                        "a module with no ABI check task is accepted".takeIf {
                            abiValidationProblems(publishedModules, everyModule, inLint + ("ocrPlugin" to null))
                                .isEmpty()
                        },
                    ) + abiValidationProblems(publishedModules, enabled, groups)
                },
                "abi_dump_names_no_atomicfu_type" to {
                    val planted =
                        mapOf(
                            "a/api/desktop/a.api" to
                                "public final class a/A {\n\tpublic final fun getCount ()Lkotlinx/atomicfu/AtomicInt;\n}\n",
                            "a/api/a.klib.api" to "final val a/count // a/count|{}count[0]\n" +
                                "    final fun <get-count>(): kotlinx.atomicfu/AtomicInt\n",
                        )
                    val kept =
                        mapOf(
                            "a/api/desktop/a.api" to "public final class a/A {\n\tpublic final fun count ()I\n}\n",
                        )
                    val dumps =
                        abiDumpDirs.flatMap { (module, dir) ->
                            dir
                                .walkTopDown()
                                .filter { it.isFile && it.name.endsWith(".api") }
                                .map { it.relativeTo(rootDir).invariantSeparatorsPath to it.readText() }
                                .toList()
                                .ifEmpty { listOf("$module/api" to "") }
                        }
                    listOfNotNull(
                        "a planted dump line naming atomicfu is accepted"
                            .takeIf { atomicfuDumpProblems(planted).size < 2 },
                        "a dump without atomicfu is rejected: ${atomicfuDumpProblems(kept)}"
                            .takeIf { atomicfuDumpProblems(kept).isNotEmpty() },
                    ) +
                        dumps.filter { (_, text) -> text.isEmpty() }.map { (path, _) -> "$path holds no ABI dump" } +
                        atomicfuDumpProblems(dumps.toMap())
                },
                "camerax_api_scope_matches_abi_dump" to {
                    val all =
                        listOf("core", "camera2", "view", "lifecycle", "video").map { "androidx.camera:camera-$it" }
                    val dump =
                        "public final class com/kashif/cameraK/frames/CameraFrameKt {\n" +
                            "\tpublic static final fun getImageProxy (Lcom/kashif/cameraK/frames/CameraFrame;)" +
                            "Landroidx/camera/core/ImageProxy;\n}\n"
                    val exact = cameraxApiScopeProblems(listOf("androidx.camera:camera-core"), dump)
                    listOfNotNull(
                        "every CameraX artifact as api, with a dump naming only camera-core, is accepted"
                            .takeIf { cameraxApiScopeProblems(all, dump).size < 4 },
                        "camera-core as api, with a dump naming it, is rejected: $exact".takeIf { exact.isNotEmpty() },
                        "a dump naming camera-core with no CameraX api is accepted"
                            .takeIf { cameraxApiScopeProblems(emptyList(), dump).isEmpty() },
                    ) + cameraxApiScopeProblems(coreAndroidApi.get(), coreAndroidDump.readText())
                },
                "sample_target_runs_the_sample_tests" to {
                    // The Sample's own tests (its commonTest and desktopTest) run under the sample target,
                    // so they count in verify.sh's summary; compiling the Sample alone tests nothing.
                    listOfNotNull(
                        "the sample target does not run :Sample:desktopTest"
                            .takeIf {
                                ":Sample:desktopTest" !in
                                    verifyTaskPaths(
                                        listOf("sample"),
                                        libraryModules,
                                        false,
                                    )
                            },
                        "the sample target runs :Sample:desktopTest for a narrower module set"
                            .takeIf {
                                ":Sample:desktopTest" in
                                    verifyTaskPaths(
                                        listOf("desktop"),
                                        libraryModules,
                                        false,
                                    )
                            },
                    )
                },
                "stability_config_lists_every_stripped_type" to {
                    if (!stabilityConfig.isFile) {
                        listOf("${stabilityConfig.relativeTo(rootDir)} does not exist")
                    } else {
                        val entries =
                            stabilityConfig
                                .readLines()
                                .map { it.substringBefore("//").trim() }
                                .filter(String::isNotEmpty)
                        listOfNotNull(
                            "${stabilityConfig.relativeTo(rootDir)} is not registered in cameraK/build.gradle.kts"
                                .takeIf { stabilityConfig.canonicalFile !in registeredStabilityFiles.get() },
                            "it lists $entries, not ${strippedStableTypes + addedStableTypes}"
                                .takeIf {
                                    entries.sorted() != (strippedStableTypes + addedStableTypes).sorted() ||
                                        entries.size != entries.toSet().size
                                },
                        )
                    }
                },
            )
        val wrong =
            cases.flatMap { (name, case) ->
                runCatching(case)
                    .getOrElse { listOf("threw $it") }
                    .map { "$name: $it" }
            }
        check(wrong.isEmpty()) { "The build-gate rules are wrong:\n" + wrong.joinToString("\n") }
    }
}

// The Maven Central token and signing passphrase were once committed in the tracked
// gradle.properties. This fails if anything could put them back in a tracked file or a run: line.
tasks.register("checkCredentialHygiene") {
    group = lintGroup
    description = "Fails if credentials could reach a tracked file, a workflow run: line or a commit"
    val script = layout.projectDirectory.file("scripts/check_credential_hygiene.py").asFile
    val root = layout.projectDirectory.asFile
    doLast {
        val process =
            ProcessBuilder("python3", "-I", script.path)
                .directory(root)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) throw GradleException("Credential hygiene check failed (exit $exit):\n$output")
        logger.lifecycle(output.trimEnd())
    }
}

// Dead text the compiler cannot see in the plugin modules: commented-out code, orphaned KDoc, and
// KDoc code samples, which belong in compiled functions referenced with @sample.
tasks.register("checkPluginSources") {
    group = lintGroup
    description = "Fails on commented-out code, orphaned KDoc or uncompiled KDoc samples in the plugin modules"
    val script = layout.projectDirectory.file("scripts/check_plugin_sources.py").asFile
    val root = layout.projectDirectory.asFile
    doLast {
        val process =
            ProcessBuilder("python3", "-I", script.path)
                .directory(root)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) throw GradleException("Plugin source check failed (exit $exit):\n$output")
        logger.lifecycle(output.trimEnd())
    }
}

// A Maven Central upload is irreversible. This fails unless the publish workflow runs only for a v*
// tag matching VERSION_NAME, after the full harness, from the protected maven-central environment.
tasks.register("checkReleaseWorkflow") {
    group = lintGroup
    description = "Fails if the publish workflow could upload an untested, untagged or unapproved commit"
    val script = layout.projectDirectory.file("scripts/check_release_workflow.py").asFile
    val root = layout.projectDirectory.asFile
    doLast {
        val process =
            ProcessBuilder("python3", "-I", script.path)
                .directory(root)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) throw GradleException("Release workflow check failed (exit $exit):\n$output")
        logger.lifecycle(output.trimEnd())
    }
}

// Policies kept in tracked files; scripts/check_repo_policy.py documents each one.
fun registerRepoPolicyCheck(
    taskName: String,
    policy: String,
    taskDescription: String,
) = tasks.register(taskName) {
    group = lintGroup
    description = taskDescription
    val script = layout.projectDirectory.file("scripts/check_repo_policy.py").asFile
    val root = layout.projectDirectory.asFile
    doLast {
        val process =
            ProcessBuilder("python3", "-I", script.path, policy)
                .directory(root)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) throw GradleException("Repository policy '$policy' failed (exit $exit):\n$output")
        logger.lifecycle(output.trimEnd())
    }
}

registerRepoPolicyCheck(
    "checkAgentPermissions",
    "agent-permissions",
    "Fails if committed agent configuration approves anything off the read-only allowlist or runs a program",
)
registerRepoPolicyCheck(
    "checkSecurityPolicy",
    "security-policy",
    "Fails if SECURITY.md misses the current release line or routes reports anywhere but private reporting",
)
registerRepoPolicyCheck(
    "checkDocsWorkflow",
    "docs-workflow",
    "Fails if pull requests skip the strict docs build or link check, or the site deploys from anything but a tag",
)
registerRepoPolicyCheck(
    "checkBuildIntegrity",
    "build-integrity",
    "Fails if the wrapper, actions or docs requirements are unpinned, Dependabot does not cover them, " +
        "or Gradle does not verify every dependency's sha256",
)
registerRepoPolicyCheck(
    "checkCatalogHygiene",
    "catalog",
    "Fails on dead or split catalog entries, plugins outside [plugins], or compose.* accessors in libraries",
)
registerRepoPolicyCheck(
    "checkNativeSettings",
    "native-settings",
    "Fails if a module without native code pins an NDK or sets jniLibs packaging",
)
registerRepoPolicyCheck(
    "checkRepositoryLinks",
    "repository-links",
    "Fails if a build file links a GitHub repository other than POM_URL, or convention-plugins returns",
)
registerRepoPolicyCheck(
    "checkModuleSources",
    "module-sources",
    "Fails if a tracked Kotlin source file lies outside every module settings.gradle.kts includes, " +
        "the iOS engine's internals are public, or an Android namespace is not one of its module's packages",
)
registerRepoPolicyCheck(
    "checkReleaseGateHarness",
    "release-gate",
    "Fails unless the release gate's Macrobenchmark module and device contract subset are built and compiled " +
        "by verify.sh",
)

// ─── Release 2.0 build conventions ──────────────────────────────────────────────────────────────
// Each check below runs in the lint target and enforces one decision for the 2.0 line.

fun trackedTextFiles(root: File): List<File> {
    val process =
        ProcessBuilder("git", "ls-files", "-z")
            .directory(root)
            .redirectErrorStream(false)
            .start()
    val listing = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    if (exit !=
        0
    ) {
        throw GradleException("git ls-files exited $exit: ${process.errorStream.bufferedReader().readText()}")
    }
    return listing
        .split('\u0000')
        .filter(String::isNotEmpty)
        .map { File(root, it) }
        .filter { file -> file.isFile && file.inputStream().use { it.readNBytes(8192) }.none { it == 0.toByte() } }
}

// 2.0 publishes no Intel-simulator klibs: no module or Sample declares that target, and nothing in
// the harness, CI, publishing or docs builds or advertises it. CHANGELOG.md records the removal.
val intelSimulatorReference = Regex("ios_?x64", RegexOption.IGNORE_CASE)
tasks.register("checkNoIntelSimulatorTarget") {
    group = lintGroup
    description = "Fails if a module declares the Intel iOS simulator target or a tracked file still refers to it"
    val kotlinModules = publishedModules + "Sample"
    val intelSimulatorTargets =
        provider {
            kotlinModules.associateWith { name ->
                project(":$name")
                    .extensions
                    .getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
                    .targets
                    .withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>()
                    .filter {
                        it.konanTarget.family == org.jetbrains.kotlin.konan.target.Family.IOS &&
                            it.konanTarget.architecture == org.jetbrains.kotlin.konan.target.Architecture.X64
                    }.map { it.name }
            }
        }
    val root = layout.projectDirectory.asFile
    doLast {
        val declared =
            intelSimulatorTargets
                .get()
                .filterValues(List<String>::isNotEmpty)
                .map { (module, targets) -> "$module declares $targets" }
        val references =
            trackedTextFiles(root)
                .filter { it.name != "CHANGELOG.md" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        if (intelSimulatorReference.containsMatchIn(line)) {
                            "${file.relativeTo(root)}:${index + 1}: ${line.trim()}"
                        } else {
                            null
                        }
                    }
                }
        check(declared.isEmpty() && references.isEmpty()) {
            "The Intel iOS simulator target was dropped in 2.0, but:\n" + (declared + references).joinToString("\n")
        }
    }
}

// The declared minSdk must equal the dependency graph's true floor. AGP never compares a library's
// minSdk with its dependencies' manifests; the merge that would only runs for apps and for unit
// tests that include Android resources. So each module reads the manifest inside every external AAR
// on its shipped and unit-test classpaths and fails if one needs a higher API level than it declares.
val androidModules = publishedModules + "Sample" + screenshotModule
val minSdkVersionAttribute = Regex("""android:minSdkVersion\s*=\s*"(\d+)"""")

data class AndroidSettings(
    val minSdk: Int?,
    val compileSdk: Int?,
    val includesAndroidResourcesInUnitTests: Boolean,
)

fun androidSettings(module: Project): AndroidSettings =
    when (val android = module.extensions.getByName("android")) {
        is com.android.build.api.dsl.LibraryExtension ->
            AndroidSettings(
                android.defaultConfig.minSdk,
                android.compileSdk,
                android.testOptions.unitTests.isIncludeAndroidResources,
            )
        is com.android.build.api.dsl.ApplicationExtension ->
            AndroidSettings(
                android.defaultConfig.minSdk,
                android.compileSdk,
                android.testOptions.unitTests.isIncludeAndroidResources,
            )
        else -> throw GradleException("${module.path}: unexpected android extension ${android::class.java.name}")
    }

fun manifestMinSdk(
    component: String,
    aar: File,
): Int {
    val manifest =
        java.util.zip.ZipFile(aar).use { zip ->
            val entry =
                zip.getEntry("AndroidManifest.xml") ?: throw GradleException("$component: no manifest in ${aar.name}")
            zip.getInputStream(entry).bufferedReader().readText()
        }
    return minSdkVersionAttribute
        .find(manifest)
        ?.groupValues
        ?.get(1)
        ?.toInt() ?: 1
}

val shippedClasspath = "releaseRuntimeClasspath"
val unitTestClasspath = "debugUnitTestRuntimeClasspath"

// What is wrong with declaring minSdk `declared` for a module whose classpaths (by configuration
// name) hold these external AARs (component display name to file). The declared value must equal the
// shipped floor: below it the app's manifest merge fails, above it the module turns away devices its
// dependencies support.
fun sdkFloorProblems(
    declared: Int,
    aarsByClasspath: Map<String, List<Pair<String, File>>>,
): List<String> {
    val floors =
        aarsByClasspath.flatMap { (configurationName, aars) ->
            aars.map { (component, aar) -> Triple(component, manifestMinSdk(component, aar), configurationName) }
        }
    val tooLow =
        floors
            .filter { (_, floor, _) -> floor > declared }
            .groupBy(
                { (component, floor, _) -> component to floor },
                { (_, _, configurationName) -> configurationName },
            ).map { (dependency, configurationNames) ->
                "${dependency.first} needs minSdk ${dependency.second} (${configurationNames.distinct().joinToString()})"
            }
    val shippedFloor = floors.filter { it.third == shippedClasspath }.maxOfOrNull { it.second }
    val tooHigh =
        if (shippedFloor != null && declared > shippedFloor) {
            listOf("nothing on $shippedClasspath needs more than minSdk $shippedFloor, so $declared is not the floor")
        } else {
            emptyList()
        }
    return tooLow + tooHigh
}

subprojects {
    if (name !in androidModules) return@subprojects
    val externalAars =
        listOf(shippedClasspath, unitTestClasspath).map { configurationName ->
            configurationName to
                provider {
                    configurations
                        .getByName(configurationName)
                        .incoming
                        .artifactView { componentFilter { it is ModuleComponentIdentifier } }
                        .artifacts.artifacts
                        .filter { it.file.extension == "aar" }
                        .map { it.id.componentIdentifier.displayName to it.file }
                }
        }
    val settings = provider { androidSettings(project) }
    val isLibrary = name in publishedModules
    val robolectricProperties =
        layout.projectDirectory
            .file(
                "src/androidUnitTest/resources/robolectric.properties",
            ).asFile
    tasks.register("checkAndroidSdkFloor") {
        group = "verification"
        description = "Fails if a dependency's manifest needs a higher minSdk than this module declares"
        doLast {
            val declared = settings.get().minSdk ?: 1
            val problems =
                sdkFloorProblems(
                    declared,
                    externalAars.associate { (name, aars) ->
                        name to aars.get()
                    },
                ).toMutableList()
            if (isLibrary && !settings.get().includesAndroidResourcesInUnitTests) {
                problems += "unit tests do not include Android resources, so their manifest merge never checks minSdk"
            }
            // Robolectric decodes OLDEST_SDK to the merged unit-test manifest's minSdk, so a suite that
            // lists it runs every test that does not pin its own SDK at the declared floor.
            if (isLibrary) {
                val sdks =
                    robolectricProperties
                        .takeIf(File::isFile)
                        ?.let { file -> java.util.Properties().apply { file.reader().use(::load) } }
                        ?.getProperty("sdk")
                        ?.split(',')
                        ?.map(String::trim)
                if (sdks == null || ("OLDEST_SDK" !in sdks && declared.toString() !in sdks)) {
                    problems +=
                        "Robolectric tests never run at minSdk $declared: ${robolectricProperties.relativeTo(
                            rootDir,
                        )} " +
                        "sets sdk=${sdks?.joinToString(",") ?: "<missing>"}; list OLDEST_SDK"
                }
            }
            check(problems.isEmpty()) { "$path declares minSdk $declared, but:\n" + problems.joinToString("\n") }
        }
    }
}

// Every library declares the same floor and the docs state it. Each library's unit tests merge their
// manifest at that floor; the Sample may sit higher only for a Sample-only dependency (kflite needs 24).
val documentedMinSdk =
    listOf(
        Regex("""\|\s*Android\s*\|\s*API (\d+)"""),
        Regex("""\*\*Android\*\*: API (\d+)"""),
        Regex("""\*\*Minimum SDK\*\*: API (\d+)"""),
        Regex("""Ensure you're using API (\d+)"""),
        Regex("""minSdk(?: is|\s*=)\s*(\d+)"""),
    )
// The floor rule run against hand-made AARs. A declared minSdk is wrong if any dependency needs more,
// and also if it is above the highest minSdk the module ships with: the floor is what the graph
// needs, not a number that merely clears it.
tasks.register("checkAndroidSdkFloorRules") {
    group = lintGroup
    description = "Runs the minSdk floor rule against fixture AARs"
    val fixtures = layout.buildDirectory.dir("sdk-floor-fixtures")
    doLast {
        val dir =
            fixtures.get().asFile.apply {
                deleteRecursively()
                mkdirs()
            }

        fun aar(
            name: String,
            minSdk: Int,
        ): Pair<String, File> {
            val file = File(dir, "$name-$minSdk.aar")
            java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
                val namespace = "http://schemas.android.com/apk/res/android"
                zip.write(
                    (
                        "<manifest xmlns:android=\"$namespace\" package=\"fixture.$name\">" +
                            "<uses-sdk android:minSdkVersion=\"$minSdk\" /></manifest>"
                    ).toByteArray(),
                )
                zip.closeEntry()
            }
            return "fixture:$name:1.0" to file
        }
        val core = aar("core", 21)
        val camera = aar("camera", 23)
        val cases =
            listOf(
                Triple("declared below a shipped dependency", 21, mapOf(shippedClasspath to listOf(core, camera))) to
                    false,
                Triple(
                    "declared below a unit-test dependency",
                    23,
                    mapOf(shippedClasspath to listOf(camera), unitTestClasspath to listOf(aar("runner", 26))),
                ) to false,
                Triple(
                    "declared equal to the shipped floor",
                    23,
                    mapOf(shippedClasspath to listOf(core, camera), unitTestClasspath to listOf(aar("junit", 19))),
                ) to true,
                Triple(
                    "declared above the shipped floor",
                    26,
                    mapOf(shippedClasspath to listOf(core, camera)),
                ) to false,
            )
        val wrong =
            cases.mapNotNull { (case, accepted) ->
                val (name, declared, aars) = case
                val problems = sdkFloorProblems(declared, aars)
                when {
                    accepted && problems.isNotEmpty() -> "$name (minSdk $declared): rejected with $problems"
                    !accepted && problems.isEmpty() -> "$name (minSdk $declared): accepted"
                    else -> null
                }
            }
        check(wrong.isEmpty()) { "The minSdk floor rule is wrong:\n" + wrong.joinToString("\n") }
    }
}

tasks.register("checkAndroidSdkFloor") {
    group = lintGroup
    description = "Fails if the libraries' minSdk values disagree, or the Sample or the docs use another one"
    dependsOn("checkAndroidSdkFloorRules")
    dependsOn(androidModules.map { ":$it:checkAndroidSdkFloor" })
    val declared = provider { androidModules.associateWith { androidSettings(project(":$it")).minSdk } }
    val root = layout.projectDirectory.asFile
    doLast {
        val levels = declared.get()
        val libraryLevels = levels.filterKeys { it in publishedModules }.values.toSet()
        val floor = libraryLevels.singleOrNull()
        check(floor != null && (levels["Sample"] ?: 0) >= floor) {
            "Every library must declare one minSdk and the Sample no lower; found $levels"
        }
        val docs =
            trackedTextFiles(root).filter { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                path == "README.MD" ||
                    path.startsWith("docs/") &&
                    !path.startsWith("docs/superpowers/") &&
                    file.extension == "md"
            }
        val stale =
            docs.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val stated =
                        documentedMinSdk
                            .firstNotNullOfOrNull { it.find(line) }
                            ?.groupValues
                            ?.get(1)
                            ?.toInt()
                    if (stated != null &&
                        stated != floor
                    ) {
                        "${file.relativeTo(root)}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
        check(stale.isEmpty()) {
            "The libraries' minSdk is $floor, but the docs say otherwise:\n" +
                stale.joinToString("\n")
        }
    }
}

// Every published module carries the same version, group, compileSdk and shared POM metadata, the Gradle
// group and version equal the published ones, a BOM pins every library at that version, the version has
// a CHANGELOG.md section, and the docs install that version. It reads the POM each module would
// publish, so it checks what consumers get rather than what a build script says.
val sharedPomFields = listOf("groupId", "version", "url", "inceptionYear", "licenses", "developers", "scm")
val perModulePomFields = listOf("artifactId", "name", "description")

// Where the source lives. Every POM's url and scm point here, not at the old CameraK repository name.
// The scm children are in the order the Maven POM schema fixes.
val repositoryUrl = "https://github.com/Kashif-E/Kamera"
val expectedScm =
    "{connection=scm:git:$repositoryUrl.git, " +
        "developerConnection=scm:git:ssh://git@github.com/Kashif-E/Kamera.git, url=$repositoryUrl}"
val installSnippet = Regex("""io\.github\.kashif-mehmood-km:([A-Za-z0-9_.-]+):([0-9][A-Za-z0-9_.-]*)""")
val catalogVersion = Regex("""^\s*camerak\s*=\s*"([^"]+)"""")

fun pomField(
    pom: org.w3c.dom.Element,
    field: String,
): String {
    fun describe(node: org.w3c.dom.Node): String {
        val children =
            (0 until node.childNodes.length).map(node.childNodes::item).filter {
                it.nodeType ==
                    org.w3c.dom.Node.ELEMENT_NODE
            }
        return if (children.isEmpty()) {
            node.textContent.trim()
        } else {
            children.joinToString(", ", "{", "}") {
                "${it.nodeName}=${describe(it)}"
            }
        }
    }
    val nodes = (0 until pom.childNodes.length).map(pom.childNodes::item).filter { it.nodeName == field }
    return nodes.singleOrNull()?.let(::describe) ?: "<missing>"
}

tasks.register("checkReleaseMetadata") {
    group = lintGroup
    description = "Fails if published modules disagree on version, group or shared POM metadata, or the docs lag"
    dependsOn(publishedModules.map { ":$it:generatePomFileForKotlinMultiplatformPublication" })
    val pomFiles =
        publishedModules.associateWith {
            project(":$it").layout.buildDirectory.file("publications/kotlinMultiplatform/pom-default.xml")
        }
    val bomProject = findProject(":$bomModule")
    if (bomProject != null) dependsOn(":$bomModule:generatePomFileForMavenPublication")
    val bomPomFile = bomProject?.layout?.buildDirectory?.file("publications/maven/pom-default.xml")
    val gradleCoordinates =
        provider {
            (publishedModules + listOfNotNull(bomProject?.name)).associateWith {
                project(":$it").let { p ->
                    "${p.group}:${p.version}"
                }
            }
        }
    val compileSdks = provider { androidModules.associateWith { androidSettings(project(":$it")).compileSdk } }
    val root = layout.projectDirectory.asFile
    doLast {
        fun parsePom(file: File) =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .documentElement
        val libraryPoms = pomFiles.mapValues { (_, file) -> parsePom(file.get().asFile) }
        val bomPom = bomPomFile?.get()?.asFile?.let(::parsePom)
        val poms = libraryPoms + listOfNotNull(bomPom?.let { bomModule to it })
        val problems = mutableListOf<String>()
        if (bomPom == null) {
            problems += "no :$bomModule module publishes a BOM, so consumers can mix core and plugin versions"
        } else {
            if (pomField(bomPom, "packaging") != "pom") {
                problems += "$bomModule: POM packaging is ${pomField(bomPom, "packaging")}, not pom"
            }
            val managed =
                bomPom
                    .getElementsByTagName("dependencyManagement")
                    .let { if (it.length == 0) null else it.item(0) as org.w3c.dom.Element }
                    ?.getElementsByTagName("dependency")
                    ?.let { dependencies ->
                        (0 until dependencies.length).map { index ->
                            val dependency = dependencies.item(index) as org.w3c.dom.Element
                            listOf("groupId", "artifactId", "version").joinToString(":") { tag ->
                                dependency
                                    .getElementsByTagName(tag)
                                    .item(0)
                                    ?.textContent
                                    ?.trim() ?: "<missing>"
                            }
                        }
                    }.orEmpty()
                    .toSet()
            val expected =
                libraryPoms.values
                    .map {
                        listOf(
                            "groupId",
                            "artifactId",
                            "version",
                        ).joinToString(":") { field -> pomField(it, field) }
                    }.toSet()
            if (managed != expected) problems += "$bomModule manages $managed, but the published modules are $expected"
        }
        sharedPomFields.forEach { field ->
            val values = poms.mapValues { (_, pom) -> pomField(pom, field) }
            if (values.values.toSet().size > 1) {
                problems +=
                    "POM <$field> differs between modules: " + values.entries.joinToString { "${it.key}=${it.value}" }
            }
        }
        poms.forEach { (module, pom) ->
            if (pomField(pom, "url") != repositoryUrl) {
                problems += "$module: POM <url> is ${pomField(pom, "url")}, not $repositoryUrl"
            }
            if (pomField(pom, "scm") != expectedScm) {
                problems += "$module: POM <scm> is ${pomField(pom, "scm")}, not $expectedScm"
            }
        }
        perModulePomFields.forEach { field ->
            poms
                .mapValues { (_, pom) -> pomField(pom, field) }
                .entries
                .groupBy({ it.value }, { it.key })
                .filterValues { it.size > 1 }
                .forEach { (value, modules) -> problems += "POM <$field> '$value' is shared by $modules" }
        }
        gradleCoordinates.get().forEach { (module, coordinates) ->
            val published = poms.getValue(module).let { "${pomField(it, "groupId")}:${pomField(it, "version")}" }
            if (coordinates !=
                published
            ) {
                problems += "$module: Gradle group:version is $coordinates but it publishes $published"
            }
        }
        compileSdks.get().let { levels ->
            if (levels.values.toSet().size > 1) problems += "compileSdk differs between modules: $levels"
        }
        val version = pomField(poms.getValue("cameraK-core"), "version")
        val changelog = File(root, "CHANGELOG.md").readLines()
        if (changelog.none { it.startsWith("## [$version]") }) problems += "CHANGELOG.md has no '## [$version]' section"
        val artifactIds = poms.values.map { pomField(it, "artifactId") }.toSet()
        trackedTextFiles(root)
            .filter { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                path == "README.MD" ||
                    path.startsWith("docs/") &&
                    !path.startsWith("docs/superpowers/") &&
                    file.extension == "md"
            }.forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val at = "${file.relativeTo(root)}:${index + 1}"
                    installSnippet.findAll(line).forEach { match ->
                        val (artifactId, documented) = match.destructured
                        if (artifactId !in artifactIds) problems += "$at: installs unknown artifact $artifactId"
                        if (documented != version) problems += "$at: installs $artifactId $documented, not $version"
                    }
                    catalogVersion.find(line)?.let { match ->
                        if (match.groupValues[1] !=
                            version
                        ) {
                            problems += "$at: catalog sets camerak = ${match.groupValues[1]}, not $version"
                        }
                    }
                }
            }
        check(problems.isEmpty()) { "Release metadata is inconsistent:\n" + problems.joinToString("\n") }
    }
}

// Licence scanners read the POM, not LICENSE, so every published POM must declare exactly the
// licence LICENSE grants. The expected POM entry is derived from LICENSE's own text.
data class PomLicense(
    val name: String,
    val url: String,
    val distribution: String,
)

fun licenseGrantedBy(text: String): PomLicense? =
    when {
        text.contains("Apache License") && text.contains("Version 2.0, January 2004") ->
            PomLicense("The Apache License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt", "repo")
        else -> null
    }

tasks.register("checkLicenseMetadata") {
    group = lintGroup
    description = "Fails if a published POM declares a licence other than the one LICENSE grants"
    dependsOn(publishedModules.map { ":$it:generatePomFileForKotlinMultiplatformPublication" })
    dependsOn(":$bomModule:generatePomFileForMavenPublication")
    val relocationProjects = relocatedArtifacts.keys.associateWith { project(":relocations:$it") }
    relocationProjects.values.forEach { dependsOn("${it.path}:generatePomFileForMavenPublication") }
    val pomFiles =
        publishedModules.associateWith {
            project(":$it").layout.buildDirectory.file("publications/kotlinMultiplatform/pom-default.xml")
        } + (bomModule to project(":$bomModule").layout.buildDirectory.file("publications/maven/pom-default.xml")) +
            relocationProjects.mapValues { (_, relocation) ->
                relocation.layout.buildDirectory.file("publications/maven/pom-default.xml")
            }
    val licenseFile = layout.projectDirectory.file("LICENSE").asFile
    doLast {
        val expected =
            licenseGrantedBy(licenseFile.readText())
                ?: throw GradleException(
                    "LICENSE is not a licence checkLicenseMetadata recognises; teach licenseGrantedBy",
                )
        val problems =
            pomFiles.mapNotNull { (module, file) ->
                val pom =
                    javax.xml.parsers.DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(file.get().asFile)
                        .documentElement
                val declared =
                    pom.getElementsByTagName("license").let { licenses ->
                        (0 until licenses.length).map { index ->
                            val license = licenses.item(index) as org.w3c.dom.Element

                            fun field(tag: String) =
                                license
                                    .getElementsByTagName(tag)
                                    .item(0)
                                    ?.textContent
                                    ?.trim()
                                    .orEmpty()
                            PomLicense(field("name"), field("url"), field("distribution"))
                        }
                    }
                if (declared == listOf(expected)) null else "$module: POM declares $declared, LICENSE grants $expected"
            }
        check(problems.isEmpty()) { "Published licence metadata contradicts LICENSE:\n" + problems.joinToString("\n") }
    }
}

// Release 2.0 is built on Compose Multiplatform 1.11. Each library's shipped desktop and Android
// classpaths must resolve Compose's runtime, foundation and ui at exactly the Compose plugin's version,
// on or above that baseline and stable. Resolving the classpaths, rather than reading the catalog,
// catches a dependency that drags Compose to another line.
val composeBaseline = "1.11.0"
val composePluginVersion =
    libs.versions.compose.multiplatform
        .get()
val composeCoreModules = listOf("runtime", "foundation", "ui").map { "org.jetbrains.compose.$it:$it" }

fun versionAtLeast(
    version: String,
    baseline: String,
): Boolean {
    fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val (actual, wanted) = parts(version) to parts(baseline)
    return (0 until maxOf(actual.size, wanted.size))
        .map { actual.getOrElse(it) { 0 }.compareTo(wanted.getOrElse(it) { 0 }) }
        .firstOrNull { it != 0 }
        ?.let { it > 0 } ?: true
}

subprojects {
    if (name !in publishedModules) return@subprojects
    val resolved =
        listOf("desktopRuntimeClasspath", "releaseRuntimeClasspath").map { configurationName ->
            configurationName to
                provider {
                    configurations
                        .getByName(configurationName)
                        .incoming.resolutionResult.allComponents
                        .mapNotNull { it.moduleVersion }
                        .map { "${it.group}:${it.name}" to it.version }
                }
        }
    tasks.register("checkComposeBaseline") {
        group = "verification"
        description = "Fails if this library ships Compose off the plugin's version"
        doLast {
            val problems = mutableListOf<String>()
            if (composePluginVersion.contains('-') || !versionAtLeast(composePluginVersion, composeBaseline)) {
                problems +=
                    "the Compose Multiplatform plugin is $composePluginVersion; 2.0 needs a stable $composeBaseline or later"
            }
            problems +=
                resolved.flatMap { (configurationName, components) ->
                    components.get().mapNotNull { (module, version) ->
                        when {
                            module in composeCoreModules && version != composePluginVersion ->
                                "$configurationName resolves $module $version, not the Compose plugin's $composePluginVersion"
                            else -> null
                        }
                    }
                }
            check(problems.isEmpty()) {
                "$path is off the 2.0 Compose baseline:\n" +
                    problems.distinct().joinToString("\n")
            }
        }
    }
}

tasks.register("checkComposeBaseline") {
    group = lintGroup
    description = "Fails if a library ships Compose off the 2.0 baseline"
    dependsOn(publishedModules.map { ":$it:checkComposeBaseline" })
}

// Each library publishes exactly one Android variant, the tested release. A debug publication would
// hand consumers' debug builds an AAR no test ran against.
tasks.register("checkPublishedVariants") {
    group = lintGroup
    description = "Fails if a library publishes an Android variant other than release"
    val publications =
        provider {
            publishedModules.associateWith { name ->
                project(":$name")
                    .extensions
                    .getByType<PublishingExtension>()
                    .publications.names
                    .sorted()
            }
        }
    doLast {
        val problems =
            publications.get().flatMap { (module, names) ->
                names.filter { it.startsWith("android") && it != "androidRelease" }.map {
                    "$module publishes $it (publications: $names)"
                } + if ("androidRelease" in names) emptyList() else listOf("$module publishes no androidRelease")
            }
        check(problems.isEmpty()) {
            "Libraries must publish only the release Android variant:\n" +
                problems.joinToString("\n")
        }
    }
}

// Libraries publish klibs. A framework binary in a library is never published and only adds link
// tasks to every build; the framework an app embeds comes from the Sample (or an umbrella module).
tasks.register("checkNoLibraryFrameworks") {
    group = lintGroup
    description = "Fails if a library module declares an iOS framework binary"
    val frameworks =
        provider {
            publishedModules.associateWith { name ->
                project(":$name")
                    .extensions
                    .getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
                    .targets
                    .withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>()
                    .flatMap { target ->
                        target.binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.Framework>().map {
                            "${target.name}.${it.name}"
                        }
                    }
            }
        }
    doLast {
        val declared = frameworks.get().filterValues(List<String>::isNotEmpty)
        check(declared.isEmpty()) {
            "Libraries publish klibs, but these declare framework binaries:\n" +
                declared.entries.joinToString("\n") { (module, binaries) -> "$module: $binaries" }
        }
    }
}

// --only screenshots runs just the screenshot classes from cameraK's desktop suite.
if ("screenshots" in verifyTargets && "desktop" !in verifyTargets) {
    project(":cameraK").tasks.withType<Test>().matching { it.name == "desktopTest" }.configureEach {
        filter.includeTestsMatching("com.kashif.cameraK.screenshots.*")
    }
}

// The tasks verifyAll runs for a selection of targets and modules, besides the lint checks (see
// lintTaskPaths). scripts/verify.sh --full, which CI runs, selects every target and every library module.
fun verifyTaskPaths(
    targets: List<String>,
    modules: List<String>,
    record: Boolean,
): List<String> {
    val paths = mutableListOf<String>()
    modules.forEach { module ->
        if ("desktop" in targets || "screenshots" in targets && module == "cameraK") {
            paths += ":$module:desktopTest"
        }
        // Tests run on debug and the simulator; release and the device targets are what ship, so
        // they are compiled too, and release is linted.
        if ("android" in targets) {
            paths +=
                listOf(":$module:testDebugUnitTest", ":$module:compileReleaseKotlinAndroid", ":$module:lintRelease")
            // The release gate's device contract subset: compiled here, run on a device by the maintainer.
            if (module == "cameraK-core") paths += ":cameraK-core:compileDebugAndroidTestKotlinAndroid"
        }
        if ("ios" in targets) {
            paths +=
                listOf(
                    ":$module:iosSimulatorArm64Test",
                    ":$module:compileKotlinIosArm64",
                )
        }
        if (listOf("desktop", "android", "ios").any { it in targets }) {
            paths += listOf(":$module:checkTestOnlyDependencies", ":$module:checkPublishedClasspath")
        }
    }
    if ("screenshots" in targets) {
        paths += ":$screenshotModule:" + if (record) "recordPaparazziDebug" else "verifyPaparazziDebug"
    }
    if ("sample" in targets) {
        paths +=
            listOf(
                // The Sample's commonTest and desktopTest; the summary counts them under the sample target.
                ":Sample:desktopTest",
                ":Sample:compileKotlinDesktop",
                ":Sample:assembleDebug",
                ":Sample:compileKotlinIosSimulatorArm64",
                ":Sample:compileKotlinIosArm64",
                ":Sample:lintDebug",
                // The framework the iOS app embeds; linking it is where Kotlin/Native runs out of heap.
                ":Sample:linkDebugFrameworkIosSimulatorArm64",
                // The non-debuggable, profileable build the release gate's Macrobenchmark measures.
                ":Sample:assembleBenchmark",
            )
    }
    return paths
}

// CI runs scripts/verify.sh --full. Tests and compiles alone miss what only Android lint reports and
// what fails only at the Kotlin/Native link of the framework an app embeds, so the full selection
// must lint every Android module and link the Sample's iOS framework.
tasks.register("checkVerifyCoverage") {
    group = lintGroup
    description = "Fails if scripts/verify.sh --full skips Android lint or the iOS framework link"
    val full = verifyTaskPaths(allVerifyTargets, publishedModules, record = false).toSet()
    doLast {
        val required =
            publishedModules.map { ":$it:lintRelease" } +
                listOf(":Sample:lintDebug", ":Sample:linkDebugFrameworkIosSimulatorArm64")
        val missing = required.filterNot(full::contains)
        check(missing.isEmpty()) { "scripts/verify.sh --full does not run:\n" + missing.joinToString("\n") }
    }
}

// Every task in lintGroup, in every project. A task's group is known only once the task is realised,
// and realising every task costs about two seconds, so this runs only when verifyAll runs lint.
fun lintTaskPaths(): List<String> =
    allprojects.flatMap { p -> p.tasks.matching { it.group == lintGroup }.map { it.path } }.sorted()

val lintChecksFile = layout.buildDirectory.file("verify/lint-checks.txt")

// The lint checks that existed when lintGroup became the only list of them, and the build-gate checks
// added since. Moving one out of the group would silently stop it running, so the lint target fails
// instead. A check runs without an entry here; listing it keeps it running, and removing a check on
// purpose means removing it here and in scripts/test_lint_minimum_set.sh too, which covers this list.
val requiredLintChecks =
    listOf(
        ":ImageSaverPlugin:checkKotlinAbi",
        ":Sample:checkMacOsCameraPackaging",
        ":analyzerPlugin:checkKotlinAbi",
        ":cameraK-core:checkKotlinAbi",
        ":cameraK-testing:checkKotlinAbi",
        ":cameraK:checkComposableStability",
        ":cameraK:checkKotlinAbi",
        ":checkAgentPermissions",
        ":checkAndroidSdkFloor",
        ":checkAndroidSdkFloorRules",
        ":checkBuildGateRules",
        ":checkBuildIntegrity",
        ":checkCatalogHygiene",
        ":checkComposeBaseline",
        ":checkCredentialHygiene",
        ":checkDocsWorkflow",
        ":checkLicenseMetadata",
        ":checkModuleSources",
        ":checkNativeSettings",
        ":checkNoIntelSimulatorTarget",
        ":checkNoLibraryFrameworks",
        ":checkPluginSources",
        ":checkPublishedVariants",
        ":checkReleaseGateHarness",
        ":checkReleaseMetadata",
        ":checkReleaseWorkflow",
        ":checkRepositoryLinks",
        ":checkSecurityPolicy",
        ":checkVerifyCoverage",
        ":ocrPlugin:checkKotlinAbi",
        ":qrScannerPlugin:checkKotlinAbi",
        ":spotlessCheck",
    )

tasks.register("verifyAll") {
    group = "verification"
    description = "Runs spotless, every module's tests on every target, screenshot tests and the Sample build"

    val paths = verifyTaskPaths(verifyTargets, verifyModules, recordScreenshots)
    val lint = "lint" in verifyTargets
    check(paths.isNotEmpty() || lint) { "verifyAll selected nothing for $verifyTargets and $verifyModules" }
    dependsOn(paths)
    if (lint) dependsOn(provider { lintTaskPaths() })
    if (verifyPublishRepository != null) {
        dependsOn(publishingProjects.map { "$it:publishAllPublicationsToVerifyScratchRepository" })
    }
}

// scripts/verify_report.py requires every lint check to have run. It reads them from this file,
// written once the task graph is known, so a run whose lint checks fail still says which they were.
gradle.taskGraph.whenReady {
    if (verifyPublishRepository != null && hasTask(":verifyAll")) {
        publishedCoordinatesFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(expectedPublishedCoordinates())))
        }
    }
    if ("lint" !in verifyTargets || !hasTask(":verifyAll")) return@whenReady
    val checks = lintTaskPaths()
    val left = requiredLintChecks.filterNot(checks::contains)
    check(left.isEmpty()) {
        "These lint checks are not in the '$lintGroup' group, so the lint target would skip them:\n" +
            left.joinToString("\n")
    }
    check(checks.isNotEmpty()) { "No task is in the '$lintGroup' group" }
    lintChecksFile.get().asFile.apply {
        parentFile.mkdirs()
        writeText(checks.joinToString("\n", postfix = "\n"))
    }
}
