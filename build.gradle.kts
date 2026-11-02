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
    if (name in libraryModules || name == bomModule) {
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
                libraryModules.forEach { add("api", project(":$it")) }
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

// What a published library hands consumers. Desktop apps get the bytedeco Java bindings a library
// imports and choose their own natives: a *-platform umbrella or a native classifier jar would ship
// every OS's binaries to every app. ocrPlugin alone imports Tesseract and Leptonica.
val nativeClassifierJar = Regex("""-(linux|macosx|windows|android|ios)-[a-z0-9_]+\.jar$""")
val coreBytedecoBindings = setOf("javacv", "javacpp", "ffmpeg", "videoinput")
val bytedecoBindings =
    libraryModules.associateWith {
        if (it == "ocrPlugin") coreBytedecoBindings + setOf("tesseract", "leptonica") else coreBytedecoBindings
    }

data class ClasspathRule(
    val module: String,
    val configurations: List<String>,
    val forbidden: Regex,
    val reason: String,
)

val forbiddenOnPublishedClasspath =
    listOf(
        ClasspathRule(
            "analyzerPlugin",
            listOf("releaseRuntimeClasspath"),
            Regex("""^com\.google\.mlkit:"""),
            "analyzerPlugin imports no ML Kit",
        ),
    )

subprojects {
    if (name !in libraryModules) return@subprojects
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
    tasks.register("checkPublishedClasspath") {
        group = "verification"
        description = "Fails if this library hands consumers natives, umbrellas or libraries it does not import"
        doLast {
            val allowed = bytedecoBindings.getValue(moduleName)
            val problems = mutableListOf<String>()
            components.getValue("desktopRuntimeClasspath").get().forEach { component ->
                val (group, artifact) = component.split(':')
                if (group == "org.bytedeco" && artifact !in allowed) {
                    problems +=
                        "desktopRuntimeClasspath -> $component: $moduleName imports only bytedeco ${allowed.sorted()}" +
                        if (artifact.endsWith("-platform")) "; a -platform umbrella ships every OS's natives" else ""
                }
            }
            val artifacts = desktopArtifacts.get()
            artifacts.filter { nativeClassifierJar.containsMatchIn(it.name) }.forEach {
                problems += "desktopRuntimeClasspath ships the native jar ${it.name}; the app chooses its natives"
            }
            forbiddenOnPublishedClasspath.filter { it.module == moduleName }.forEach { rule ->
                rule.configurations.forEach { configurationName ->
                    components.getValue(configurationName).get().filter(rule.forbidden::containsMatchIn).forEach {
                        problems += "$configurationName -> $it: ${rule.reason}"
                    }
                }
            }
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

// A Maven Central upload is irreversible. This fails unless the publish workflow runs only for a v*
// tag matching VERSION_NAME, after the full harness, from the protected maven-central environment.
tasks.register("checkReleaseWorkflow") {
    group = "verification"
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

// The declared minSdk must equal the dependency graph's true floor. AGP never compares a library's
// minSdk with its dependencies' manifests; the merge that would only runs for apps and for unit
// tests that include Android resources. So each module reads the manifest inside every external AAR
// on its shipped and unit-test classpaths and fails if one needs a higher API level than it declares.
val androidModules = libraryModules + "Sample" + screenshotModule
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
    val isLibrary = name in libraryModules
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
    group = "verification"
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
    group = "verification"
    description = "Fails if the libraries' minSdk values disagree, or the Sample or the docs use another one"
    dependsOn("checkAndroidSdkFloorRules")
    dependsOn(androidModules.map { ":$it:checkAndroidSdkFloor" })
    val declared = provider { androidModules.associateWith { androidSettings(project(":$it")).minSdk } }
    val root = layout.projectDirectory.asFile
    doLast {
        val levels = declared.get()
        val libraryLevels = levels.filterKeys { it in libraryModules }.values.toSet()
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
    group = "verification"
    description = "Fails if published modules disagree on version, group or shared POM metadata, or the docs lag"
    dependsOn(libraryModules.map { ":$it:generatePomFileForKotlinMultiplatformPublication" })
    val pomFiles =
        libraryModules.associateWith {
            project(":$it").layout.buildDirectory.file("publications/kotlinMultiplatform/pom-default.xml")
        }
    val bomProject = findProject(":$bomModule")
    if (bomProject != null) dependsOn(":$bomModule:generatePomFileForMavenPublication")
    val bomPomFile = bomProject?.layout?.buildDirectory?.file("publications/maven/pom-default.xml")
    val gradleCoordinates =
        provider {
            (libraryModules + listOfNotNull(bomProject?.name)).associateWith {
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
        val version = pomField(poms.getValue("cameraK"), "version")
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
    group = "verification"
    description = "Fails if a published POM declares a licence other than the one LICENSE grants"
    dependsOn(libraryModules.map { ":$it:generatePomFileForKotlinMultiplatformPublication" })
    dependsOn(":$bomModule:generatePomFileForMavenPublication")
    val pomFiles =
        libraryModules.associateWith {
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
    if (name !in libraryModules) return@subprojects
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
    group = "verification"
    description = "Fails if a library ships Compose or Coil off the 2.0 baseline"
    dependsOn(libraryModules.map { ":$it:checkComposeBaseline" })
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
        paths +=
            listOf(
                ":spotlessCheck",
                ":checkCredentialHygiene",
                ":checkReleaseWorkflow",
                ":checkNoIntelSimulatorTarget",
                ":checkAndroidSdkFloor",
                ":checkReleaseMetadata",
                ":checkLicenseMetadata",
                ":checkComposeBaseline",
            )
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
                )
        }
        if (listOf("desktop", "android", "ios").any { it in verifyTargets }) {
            paths += listOf(":$module:checkTestOnlyDependencies", ":$module:checkPublishedClasspath")
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
