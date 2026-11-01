plugins {
    alias(libs.plugins.multiplatform).apply(false)
    alias(libs.plugins.android.library).apply(false)
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.paparazzi) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.spotless)
}

buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.composeGradle)
    }
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
    listOf("cameraK", "ImageSaverPlugin", "qrScannerPlugin", "ocrPlugin", "videoRecorderPlugin", "analyzerPlugin")
val screenshotModule = "cameraKScreenshotTests"
val allVerifyTargets = listOf("desktop", "android", "ios", "screenshots", "lint", "sample")

fun csvProperty(name: String): List<String>? =
    providers
        .gradleProperty(name)
        .orNull
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)

val verifyModules = csvProperty("camerak.verify.modules") ?: libraryModules
(verifyModules - libraryModules).let { unknown ->
    require(unknown.isEmpty()) { "Unknown module(s) $unknown; expected one of $libraryModules" }
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
// consumers: the desktop and both Android runtime classpaths (cameraK publishes debug as well as
// release) and the klibs every iOS target compiles against.
val testOnlyModules =
    listOf(
        Regex("""^org\.jetbrains\.kotlinx:kotlinx-coroutines-test"""),
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
        "iosX64CompileKlibraries",
        "iosArm64CompileKlibraries",
        "iosSimulatorArm64CompileKlibraries",
    )
subprojects {
    if (name !in libraryModules) return@subprojects
    val runtimeGraphs =
        publishedGraphs.map { configurationName ->
            configurationName to
                provider { configurations.getByName(configurationName) }
                    .flatMap { it.incoming.resolutionResult.rootComponent }
        }
    tasks.register("checkTestOnlyDependencies") {
        group = "verification"
        description = "Fails if a test library is on a classpath a published variant hands to consumers"
        doLast {
            val leaks =
                runtimeGraphs.flatMap { (configurationName, root) ->
                    val seen = mutableSetOf<ResolvedComponentResult>()
                    val queue = ArrayDeque(listOf(root.get()))
                    while (queue.isNotEmpty()) {
                        val component = queue.removeFirst()
                        if (!seen.add(component)) continue
                        component.dependencies
                            .filterIsInstance<ResolvedDependencyResult>()
                            .forEach { queue.add(it.selected) }
                    }
                    seen.mapNotNull { component ->
                        val id = component.moduleVersion?.let { "${it.group}:${it.name}" } ?: return@mapNotNull null
                        if (testOnlyModules.any { it.containsMatchIn(id) }) "$configurationName -> $id" else null
                    }
                }
            check(leaks.isEmpty()) { "Test-only libraries on a published classpath:\n" + leaks.joinToString("\n") }
        }
    }
}

// The Maven Central token and signing passphrase were once committed in the tracked
// gradle.properties. This fails if anything could put them back in a tracked file or a run: line.
tasks.register("checkCredentialHygiene") {
    group = "verification"
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
    group = "verification"
    description = "Fails if a module declares the Intel iOS simulator target or a tracked file still refers to it"
    val kotlinModules = libraryModules + "Sample"
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

// --only screenshots runs just the screenshot classes from cameraK's desktop suite.
if ("screenshots" in verifyTargets && "desktop" !in verifyTargets) {
    project(":cameraK").tasks.withType<Test>().matching { it.name == "desktopTest" }.configureEach {
        filter.includeTestsMatching("com.kashif.cameraK.screenshots.*")
    }
}

tasks.register("verifyAll") {
    group = "verification"
    description = "Runs spotless, every module's tests on every target, screenshot tests and the Sample build"

    val paths = mutableListOf<String>()
    if ("lint" in
        verifyTargets
    ) {
        paths += listOf(":spotlessCheck", ":checkCredentialHygiene", ":checkNoIntelSimulatorTarget")
    }
    verifyModules.forEach { module ->
        if ("desktop" in verifyTargets || "screenshots" in verifyTargets && module == "cameraK") {
            paths += ":$module:desktopTest"
        }
        // Tests run on debug and the simulator; release and the device targets are what ship, so
        // they are compiled too.
        if ("android" in verifyTargets) {
            paths += listOf(":$module:testDebugUnitTest", ":$module:compileReleaseKotlinAndroid")
        }
        if ("ios" in verifyTargets) {
            paths +=
                listOf(
                    ":$module:iosSimulatorArm64Test",
                    ":$module:compileKotlinIosArm64",
                    ":$module:compileKotlinIosX64",
                )
        }
        if (listOf("desktop", "android", "ios").any { it in verifyTargets }) {
            paths += ":$module:checkTestOnlyDependencies"
        }
    }
    if ("screenshots" in verifyTargets) {
        paths += ":$screenshotModule:" + if (recordScreenshots) "recordPaparazziDebug" else "verifyPaparazziDebug"
    }
    if ("sample" in verifyTargets) {
        paths +=
            listOf(
                ":Sample:compileKotlinDesktop",
                ":Sample:assembleDebug",
                ":Sample:compileKotlinIosSimulatorArm64",
                ":Sample:compileKotlinIosArm64",
            )
    }
    check(paths.isNotEmpty()) { "verifyAll selected nothing for targets $verifyTargets and modules $verifyModules" }
    dependsOn(paths)
}
