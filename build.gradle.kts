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
        "videoRecorderPlugin",
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
    if (name in publishedModules || name == bomModule) {
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
fun cameraComposeVersionProblems(catalog: String): List<String> = emptyList()

// The types camerak-core no longer annotates for Compose; cameraK/compose-stability.conf lists them.
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
        "com.kashif.cameraK.video.VideoCaptureResult",
        "com.kashif.cameraK.video.VideoCaptureResult.*",
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
    val stabilityConfig = layout.projectDirectory.file("cameraK/compose-stability.conf").asFile
    val sampleXcodeProject = layout.projectDirectory.file("iosApp/iosApp.xcodeproj/project.pbxproj").asFile
    val versionCatalog = layout.projectDirectory.file("gradle/libs.versions.toml").asFile
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
    "Fails if the wrapper, actions or docs requirements are unpinned, or Dependabot does not cover them",
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
    "Fails if a tracked Kotlin source file lies outside every module settings.gradle.kts includes",
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
    val pomFiles =
        publishedModules.associateWith {
            project(":$it").layout.buildDirectory.file("publications/kotlinMultiplatform/pom-default.xml")
        } + (bomModule to project(":$bomModule").layout.buildDirectory.file("publications/maven/pom-default.xml"))
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

// Release 2.0 is built on Compose Multiplatform 1.11 and Coil 3.5. Each library's shipped desktop and
// Android classpaths must resolve Compose's runtime, foundation and ui at exactly the Compose plugin's
// version, on or above that baseline and stable, and Coil on or above its baseline. Resolving the
// classpaths, rather than reading the catalog, catches a dependency that drags Compose to another line.
val composeBaseline = "1.11.0"
val coilBaseline = "3.5.0"
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
        description = "Fails if this library ships Compose off the plugin's version or Coil below the 2.0 baseline"
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
                            module.startsWith("io.coil-kt.coil3:") && !versionAtLeast(version, coilBaseline) ->
                                "$configurationName resolves $module $version; 2.0 needs Coil $coilBaseline or later"
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
    description = "Fails if a library ships Compose or Coil off the 2.0 baseline"
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
                ":Sample:compileKotlinDesktop",
                ":Sample:assembleDebug",
                ":Sample:compileKotlinIosSimulatorArm64",
                ":Sample:compileKotlinIosArm64",
                ":Sample:lintDebug",
                // The framework the iOS app embeds; linking it is where Kotlin/Native runs out of heap.
                ":Sample:linkDebugFrameworkIosSimulatorArm64",
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
        ":Sample:checkMacOsCameraPackaging",
        ":cameraK:checkComposableStability",
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
        ":checkReleaseMetadata",
        ":checkReleaseWorkflow",
        ":checkRepositoryLinks",
        ":checkSecurityPolicy",
        ":checkVerifyCoverage",
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
}

// scripts/verify_report.py requires every lint check to have run. It reads them from this file,
// written once the task graph is known, so a run whose lint checks fail still says which they were.
gradle.taskGraph.whenReady {
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
