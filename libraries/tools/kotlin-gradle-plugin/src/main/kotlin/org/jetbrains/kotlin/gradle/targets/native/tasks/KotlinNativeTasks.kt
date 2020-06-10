/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("PackageDirectoryMismatch") // Old package for compatibility
package org.jetbrains.kotlin.gradle.tasks

import groovy.lang.Closure
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.*
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.api.tasks.compile.AbstractCompile
import org.jetbrains.kotlin.compilerRunner.*
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonToolOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.NativeCacheKind
import org.jetbrains.kotlin.gradle.internal.ensureParentDirsCreated
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.LanguageSettingsBuilder
import org.jetbrains.kotlin.gradle.plugin.cocoapods.asValidFrameworkName
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.plugin.sources.DefaultLanguageSettingsBuilder
import org.jetbrains.kotlin.gradle.utils.*
import org.jetbrains.kotlin.gradle.utils.klibModuleName
import org.jetbrains.kotlin.konan.library.KLIB_INTEROP_IR_PROVIDER_IDENTIFIER
import org.jetbrains.kotlin.konan.properties.saveToFile
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.konan.target.CompilerOutputKind.*
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.library.*
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.jetbrains.kotlin.konan.file.File as KFile
import org.jetbrains.kotlin.util.Logger as KLogger

// TODO: It's just temporary tasks used while KN isn't integrated with Big Kotlin compilation infrastructure.
// region Useful extensions
internal fun MutableList<String>.addArg(parameter: String, value: String) {
    add(parameter)
    add(value)
}

internal fun MutableList<String>.addArgs(parameter: String, values: Iterable<String>) {
    values.forEach {
        addArg(parameter, it)
    }
}

internal fun MutableList<String>.addArgIfNotNull(parameter: String, value: String?) {
    if (value != null) {
        addArg(parameter, value)
    }
}

internal fun MutableList<String>.addKey(key: String, enabled: Boolean) {
    if (enabled) {
        add(key)
    }
}

internal fun MutableList<String>.addFileArgs(parameter: String, values: FileCollection) {
    values.files.forEach {
        addArg(parameter, it.canonicalPath)
    }
}

internal fun MutableList<String>.addFileArgs(parameter: String, values: Collection<FileCollection>) {
    values.forEach {
        addFileArgs(parameter, it)
    }
}

private fun File.providedByCompiler(project: Project): Boolean =
    toPath().startsWith(project.file(project.konanHome).resolve("klib").toPath())

// We need to filter out interop duplicates because we create copy of them for IDE.
// TODO: Remove this after interop rework.
private fun FileCollection.filterOutPublishableInteropLibs(project: Project): FileCollection {
    val libDirectories = project.rootProject.allprojects.map { it.buildDir.resolve("libs").absoluteFile.toPath() }
    return filter { file ->
        !(file.name.contains("-cinterop-") && libDirectories.any { file.toPath().startsWith(it) })
    }
}

/**
 * We pass to the compiler:
 *
 *    - Only *.klib files and directories (normally containing an unpacked klib).
 *      A dependency configuration may contain jar files
 *      (e.g. when a common artifact was directly added to commonMain source set).
 *      So, we need to filter out such artifacts.
 *
 *    - Only existing files. We don't compile a klib if there are no sources
 *      for it (NO-SOURCE check). So we need to take this case into account
 *      and skip libraries that were not compiled. See also: GH-2617 (K/N repo).
 */
private fun Collection<File>.filterKlibsPassedToCompiler(project: Project) = filter {
    (it.extension == "klib" || it.isDirectory) && it.exists()
}

// endregion
abstract class AbstractKotlinNativeCompile<T : KotlinCommonToolOptions, K : AbstractKotlinNativeCompilation> : AbstractCompile() {

    init {
        sourceCompatibility = "1.6"
        targetCompatibility = "1.6"
    }

    @get:Internal
    abstract val compilation: Provider<K>

    // region inputs/outputs
    @get:Input
    abstract val outputKind: CompilerOutputKind

    @get:Input
    abstract val optimized: Boolean

    @get:Input
    abstract val debuggable: Boolean

    @get:Internal
    abstract val baseName: String

    // Inputs and outputs
    @get:InputFiles
    val libraries: FileCollection by compilation.map {
        // Avoid resolving these dependencies during task graph construction when we can't build the target:
        if (it.konanTarget.enabledOnCurrentHost)
            it.compileDependencyFiles.filterOutPublishableInteropLibs(project)
        else project.files()
    }


    override fun getClasspath(): FileCollection = libraries
    override fun setClasspath(configuration: FileCollection?) {
        throw UnsupportedOperationException("Setting classpath directly is unsupported.")
    }

    @get:Input
    val target: String by compilation.map { it.konanTarget.name }

    // region Compiler options.
    @get:Internal
    abstract val kotlinOptions: T

    abstract fun kotlinOptions(fn: T.() -> Unit)
    abstract fun kotlinOptions(fn: Closure<*>)

    @get:Input
    abstract val additionalCompilerOptions: Provider<Collection<String>>

    @get:Internal
    val languageSettings: LanguageSettingsBuilder by lazy {
        compilation.get().defaultSourceSet.languageSettings
    }

    @get:Input
    val progressiveMode: Boolean
        get() = languageSettings.progressiveMode
    // endregion.

    @get:Input
    val enableEndorsedLibs by compilation.map { it.enableEndorsedLibs }

    val kotlinNativeVersion: String
        @Input get() = project.konanVersion.toString()

    // OutputFile is located under the destinationDir, so there is no need to register it as a separate output.
    @Internal
    val outputFile: Provider<File> = project.provider {
        val konanTarget = compilation.get().konanTarget

        val prefix = outputKind.prefix(konanTarget)
        val suffix = outputKind.suffix(konanTarget)
        val filename = "$prefix${baseName}$suffix".let {
            when {
                outputKind == FRAMEWORK ->
                    it.asValidFrameworkName()
                outputKind in listOf(STATIC, DYNAMIC) || outputKind == PROGRAM && konanTarget == KonanTarget.WASM32 ->
                    it.replace('-', '_')
                else -> it
            }
        }

        destinationDir.resolve(filename)
    }

    // endregion
    @Internal
    val compilerPluginOptions = CompilerPluginOptions()

    val compilerPluginCommandLine
        @Input get() = compilerPluginOptions.arguments

    @Optional
    @InputFiles
    var compilerPluginClasspath: FileCollection? = null

    // Used by IDE via reflection.
    val serializedCompilerArguments: List<String>
        @Internal get() = buildCommonArgs()

    // Used by IDE via reflection.
    val defaultSerializedCompilerArguments: List<String>
        @Internal get() = buildCommonArgs(true)

    // Args used by both the compiler and IDEA.
    protected open fun buildCommonArgs(defaultsOnly: Boolean = false): List<String> = mutableListOf<String>().apply {
        add("-Xmulti-platform")

        if (!enableEndorsedLibs) {
            add("-no-endorsed-libs")
        }

        // Compiler plugins.
        compilerPluginClasspath?.let { pluginClasspath ->
            pluginClasspath.map { it.canonicalPath }.sorted().forEach { path ->
                add("-Xplugin=$path")
            }
            compilerPluginOptions.arguments.forEach {
                add("-P")
                add(it)
            }
        }

        // kotlin options
        addKey("-Werror", kotlinOptions.allWarningsAsErrors)
        addKey("-nowarn", kotlinOptions.suppressWarnings)
        addKey("-verbose", kotlinOptions.verbose)
        addKey("-progressive", progressiveMode)

        if (!defaultsOnly) {
            addAll(additionalCompilerOptions.get())
        }

        (compilation.get().defaultSourceSet.languageSettings as? DefaultLanguageSettingsBuilder)?.run {
            addAll(freeCompilerArgs)
        }
    }

    @get:Input
    @get:Optional
    internal val konanTargetsForManifest: String by project.provider {
        (compilation as? KotlinSharedNativeCompilation)
            ?.konanTargets
            ?.joinToString(separator = " ") { it.visibleName }
            .orEmpty()
    }

    @get:Internal
    internal val manifestFile: Provider<File>
        get() = project.provider {
            val inputManifestFile = project.buildDir.resolve("tmp/$name/inputManifest")
            inputManifestFile
        }

    // Args passed to the compiler only (except sources).
    protected open fun buildCompilerArgs(): List<String> = mutableListOf<String>().apply {
        addKey("-opt", optimized)
        addKey("-g", debuggable)
        addKey("-ea", debuggable)

        addArg("-target", target)
        addArg("-p", outputKind.name.toLowerCase())

        if (compilation.get() is KotlinSharedNativeCompilation) {
            add("-Xexpect-actual-linker")
            add("-Xmetadata-klib")
            addArg("-manifest", manifestFile.get().absolutePath)
            add("-no-default-libs")
        }

        addArg("-o", outputFile.get().absolutePath)

        // Libraries.
        libraries.files.filterKlibsPassedToCompiler(project).forEach { library ->
            addArg("-l", library.absolutePath)
        }
    }

    // Sources passed to the compiler.
    // We add sources after all other arguments to make the command line more readable and simplify debugging.
    protected abstract fun buildSourceArgs(): List<String>

    private fun buildArgs(): List<String> =
        buildCompilerArgs() + buildCommonArgs() + buildSourceArgs()

    @TaskAction
    open fun compile() {
        val output = outputFile.get()
        output.parentFile.mkdirs()

        if (compilation.get() is KotlinSharedNativeCompilation) {
            val manifestFile: File = manifestFile.get()
            manifestFile.ensureParentDirsCreated()
            val properties = java.util.Properties()
            properties[KLIB_PROPERTY_NATIVE_TARGETS] = konanTargetsForManifest
            properties.saveToFile(org.jetbrains.kotlin.konan.file.File(manifestFile.toPath()))
        }

        KotlinNativeCompilerRunner(project).run(buildArgs())
    }
}

/**
 * A task producing a klibrary from a compilation.
 */
open class KotlinNativeCompile : AbstractKotlinNativeCompile<KotlinCommonOptions, AbstractKotlinNativeCompilation>(),
    KotlinCompile<KotlinCommonOptions> {
    @Internal
    @Transient // can't be serialized for Gradle configuration avoidance
    final override val compilation: Property<AbstractKotlinNativeCompilation> = project.newPropertyInit()

    @get:Input
    override val outputKind = LIBRARY

    @get:Input
    override val optimized = false

    @get:Input
    override val debuggable = true

    @get:Internal
    override val baseName: String by compilation.map { if (it.isMainCompilation) project.name else "${project.name}_${it.name}" }

    // Store as an explicit provider in order to allow Gradle Instant Execution to capture the state
    private val allSourceProvider = compilation.map { project.files(it.allSources).asFileTree }

    @get:Input
    val moduleName: String by project.provider {
        project.klibModuleName(baseName)
    }

    @get:Input
    val shortModuleName: String by project.provider { baseName }

    // Inputs and outputs.
    // region Sources.
    @InputFiles
    @SkipWhenEmpty
    override fun getSource(): FileTree = allSourceProvider.get()

    private val commonSources: FileCollection by lazy {
        // Already taken into account in getSources method.
        project.files(compilation.map { it.commonSources }).asFileTree
    }

    private val friendModule: FileCollection by compilation.map { compilationInstance ->
        project.files(
            compilationInstance.associateWithTransitiveClosure.map { compilationInstance.output.allOutputs },
            compilationInstance.friendArtifacts
        )
    }
    // endregion.

    // region Language settings imported from a SourceSet.
    val languageVersion: String?
        @Optional @Input get() = languageSettings.languageVersion

    val apiVersion: String?
        @Optional @Input get() = languageSettings.apiVersion

    val enabledLanguageFeatures: Set<String>
        @Input get() = languageSettings.enabledLanguageFeatures

    val experimentalAnnotationsInUse: Set<String>
        @Input get() = languageSettings.experimentalAnnotationsInUse
    // endregion.

    // region Kotlin options.
    private inner class NativeCompileOptions : KotlinCommonOptions {
        override var apiVersion: String?
            get() = languageSettings.apiVersion
            set(value) {
                languageSettings.apiVersion = value
            }

        override var languageVersion: String?
            get() = this@KotlinNativeCompile.languageVersion
            set(value) {
                languageSettings.languageVersion = value
            }

        override var allWarningsAsErrors: Boolean = false
        override var suppressWarnings: Boolean = false
        override var verbose: Boolean = false

        override var freeCompilerArgs: List<String> = listOf()
    }

    @get:Input
    override val additionalCompilerOptions: Provider<Collection<String>> = project.provider {
        kotlinOptions.freeCompilerArgs
    }

    override val kotlinOptions: KotlinCommonOptions by lazy { NativeCompileOptions() }

    override fun kotlinOptions(fn: KotlinCommonOptions.() -> Unit) {
        kotlinOptions.fn()
    }

    override fun kotlinOptions(fn: Closure<*>) {
        fn.delegate = kotlinOptions
        fn.call()
    }
    // endregion.

    // region Building args.
    override fun buildCommonArgs(defaultsOnly: Boolean): List<String> = mutableListOf<String>().apply {
        addAll(super.buildCommonArgs(defaultsOnly))

        // Language features.
        addArgIfNotNull("-language-version", languageVersion)
        addArgIfNotNull("-api-version", apiVersion)
        enabledLanguageFeatures.forEach { featureName ->
            add("-XXLanguage:+$featureName")
        }
        experimentalAnnotationsInUse.forEach { annotationName ->
            add("-Xopt-in=$annotationName")
        }
    }

    override fun buildCompilerArgs(): List<String> = mutableListOf<String>().apply {
        addAll(super.buildCompilerArgs())

        // Configure FQ module name to avoid cyclic dependencies in klib manifests (see KT-36721).
        addArg("-module-name", moduleName)
        add("-Xshort-module-name=$shortModuleName")
        val friends = friendModule.files
        if (friends != null && friends.isNotEmpty()) {
            addArg("-friend-modules", friends.map { it.absolutePath }.joinToString(File.pathSeparator))
        }
    }

    override fun buildSourceArgs(): List<String> = mutableListOf<String>().apply {
        addAll(getSource().map { it.absolutePath })
        if (!commonSources.isEmpty) {
            add("-Xcommon-sources=${commonSources.map { it.absolutePath }.joinToString(separator = ",")}")
        }
    }
    // endregion.
}

/**
 * A task producing a final binary from a compilation.
 */
open class KotlinNativeLink : AbstractKotlinNativeCompile<KotlinCommonToolOptions, KotlinNativeCompilation>() {

    @get:Internal
    @Transient // can't be serialized for Gradle configuration avoidance
    final override val compilation: Provider<KotlinNativeCompilation> = project.provider { binary.compilation }

    init {
        @Suppress("LeakingThis")
        dependsOn(compilation.map { if (!linkFromSources) listOf(it.compileKotlinTaskHolder) else emptyList() })
    }

    @Internal
    @Transient
    lateinit var binary: NativeBinary

    @get:Internal // Taken into account by getSources().
    val intermediateLibrary: File by compilation.map { it.compileKotlinTask.outputFile }.get()

    // explicitly store the provider in order for Gradle Instant Execution to capture the state
    private val sourceProvider = compilation.map { compilationInstance ->
        if (linkFromSources) {
            // Allow a user to force the old behaviour of a link task.
            // It's better to keep this flag in 1.3.70 to be able to workaroud probable klib serialization bugs.
            // TODO: Remove in 1.4.
            project.files(compilationInstance.allSources).asFileTree
        } else {
            project.files(intermediateLibrary).asFileTree
        }
    }

    @InputFiles
    @SkipWhenEmpty
    override fun getSource(): FileTree = sourceProvider.get()

    @OutputDirectory
    override fun getDestinationDir(): File {
        return binary.outputDirectory
    }

    override fun setDestinationDir(destinationDir: File) {
        binary.outputDirectory = destinationDir
    }

    @get:Input
    override val outputKind: CompilerOutputKind by project.provider {
        binary.outputKind.compilerOutputKind
    }

    @get:Input
    override val optimized: Boolean by project.provider {
        binary.optimized
    }

    @get:Input
    override val debuggable: Boolean by project.provider {
        binary.debuggable
    }

    @get:Internal
    override val baseName by project.provider { binary.baseName }

    @get:Input
    protected val konanCacheKind: NativeCacheKind
        get() = project.konanCacheKind

    inner class NativeLinkOptions : KotlinCommonToolOptions {
        override var allWarningsAsErrors: Boolean = false
        override var suppressWarnings: Boolean = false
        override var verbose: Boolean = false
        override var freeCompilerArgs: List<String> = listOf()
    }

    // We propagate compilation free args to the link task for now (see KT-33717).
    @get:Input
    override val additionalCompilerOptions: Provider<Collection<String>> = compilation.map { compilationInstance ->
        kotlinOptions.freeCompilerArgs + compilationInstance.kotlinOptions.freeCompilerArgs
    }

    override val kotlinOptions: KotlinCommonToolOptions = NativeLinkOptions()

    override fun kotlinOptions(fn: KotlinCommonToolOptions.() -> Unit) {
        kotlinOptions.fn()
    }

    override fun kotlinOptions(fn: Closure<*>) {
        fn.delegate = kotlinOptions
        fn.call()
    }

    //region language settings inputs for the [linkFromSources] mode.
    // TODO: Remove in 1.3.70.
    @get:Optional
    @get:Input
    val languageVersion: String?
        get() = languageSettings.languageVersion.takeIf { linkFromSources }

    @get:Optional
    @get:Input
    val apiVersion: String?
        get() = languageSettings.apiVersion.takeIf { linkFromSources }

    @get:Optional
    @get:Input
    val enabledLanguageFeatures: Set<String>?
        get() = languageSettings.enabledLanguageFeatures.takeIf { linkFromSources }

    @get:Optional
    @get:Input
    val experimentalAnnotationsInUse: Set<String>?
        get() = languageSettings.experimentalAnnotationsInUse.takeIf { linkFromSources }
    // endregion.

    // Binary-specific options.
    @get:Optional
    @get:Input
    val entryPoint: String? by project.provider {
        (binary as? Executable)?.entryPoint
    }

    @get:Input
    val linkerOpts: List<String> by project.provider {
        binary.linkerOpts
    }

    @get:Input
    val processTests: Boolean by project.provider {
        binary is TestExecutable
    }

    @get:InputFiles
    val exportLibraries: FileCollection by project.provider {
        binary.let {
            if (it is AbstractNativeLibrary) {
                project.configurations.getByName(it.exportConfigurationName)
            } else {
                project.files()
            }
        }
    }

    @get:Input
    val isStaticFramework: Boolean by project.provider {
        binary.let { it is Framework && it.isStatic }
    }

    @get:Input
    val embedBitcode: Framework.BitcodeEmbeddingMode by project.provider {
        (binary as? Framework)?.embedBitcode ?: Framework.BitcodeEmbeddingMode.DISABLE
    }

    // This property allows a user to force the old behaviour of a link task
    // to workaround issues that may occur after switching to the two-stage linking.
    // If it is specified, the final binary is built directly from sources instead of a klib.
    // TODO: Remove it in 1.3.70.
    private val linkFromSources: Boolean
        get() = project.hasProperty(LINK_FROM_SOURCES_PROPERTY)

    override fun buildCompilerArgs(): List<String> = mutableListOf<String>().apply {
        addAll(super.buildCompilerArgs())

        addAll(CacheBuilder(project, binary).buildCompilerArgs())

        addKey("-tr", processTests)
        addArgIfNotNull("-entry", entryPoint)
        when (embedBitcode) {
            Framework.BitcodeEmbeddingMode.MARKER -> add("-Xembed-bitcode-marker")
            Framework.BitcodeEmbeddingMode.BITCODE -> add("-Xembed-bitcode")
            else -> { /* Do nothing. */ }
        }
        linkerOpts.forEach {
            addArg("-linker-option", it)
        }
        exportLibraries.files.filterKlibsPassedToCompiler(project).forEach {
            add("-Xexport-library=${it.absolutePath}")
        }
        addKey("-Xstatic-framework", isStaticFramework)

        // Allow a user to force the old behaviour of a link task.
        // TODO: Remove in 1.3.70.
        if (!linkFromSources) {
            languageSettings.let {
                addArgIfNotNull("-language-version", it.languageVersion)
                addArgIfNotNull("-api-version", it.apiVersion)
                it.enabledLanguageFeatures.forEach { featureName ->
                    add("-XXLanguage:+$featureName")
                }
                it.experimentalAnnotationsInUse.forEach { annotationName ->
                    add("-Xopt-in=$annotationName")
                }
            }
        }
    }

    private val friendCompilations = compilation.map { it.associateWithTransitiveClosure.toList() }

    private val friendFiles: FileCollection = project.files(
        friendCompilations.get().map { friendCompilation -> friendCompilation.output.allOutputs },
        compilation.map { it.friendArtifacts }
    )

    private val allSourcesProvider = compilation.map { it.allSources }
    private val commonSourcesProvider = compilation.map { it.commonSources }


//    val friendFiles = friendCompilations.map {
//        if (it.isNotEmpty())
//            project.files(
//                project.provider { it.map { it.output.allOutputs } + compilation.friendArtifacts }
//            )
//        else null
//    }

    override fun buildSourceArgs(): List<String> {
        return if (!linkFromSources) {
            listOf("-Xinclude=${intermediateLibrary.absolutePath}")
        } else {
            // Allow a user to force the old behaviour of a link task.
            // TODO: Remove in 1.3.70.
            mutableListOf<String>().apply {
                if (!friendFiles.isEmpty) {
                    addArg("-friend-modules", friendFiles.joinToString(File.pathSeparator) { it.absolutePath })
                }

                addAll(project.files(allSourcesProvider.get()).map { it.absolutePath })

                val commonSources = commonSourcesProvider.get()
                if (!commonSources.isEmpty) {
                    add("-Xcommon-sources=${commonSources.joinToString(separator = ",") { it.absolutePath }}")
                }
            }
        }
    }

    private val apiFilesProvider: Provider<List<File>> = compilation.map {
        project.configurations.getByName(it.apiConfigurationName).files.filterKlibsPassedToCompiler(project)
    }

    private val binaryNameProvider = project.provider {
        binary.name
    }

    private fun validatedExportedLibraries() {
        val exportConfiguration = exportLibraries as? Configuration ?: return
        val apiFiles = apiFilesProvider.get()

        val failed = mutableSetOf<Dependency>()
        exportConfiguration.allDependencies.forEach {
            val dependencyFiles = exportConfiguration.files(it).filterKlibsPassedToCompiler(project)
            if (!apiFiles.containsAll(dependencyFiles)) {
                failed.add(it)
            }
        }

        check(failed.isEmpty()) {
            val failedDependenciesList = failed.joinToString(separator = "\n") {
                when (it) {
                    is FileCollectionDependency -> "|Files: ${it.files.files}"
                    is ProjectDependency -> "|Project ${it.dependencyProject.path}"
                    else -> "|${it.group}:${it.name}:${it.version}"
                }
            }

            """
                |Following dependencies exported in the ${binaryNameProvider.get()} binary are not specified as API-dependencies of a corresponding source set:
                |
                $failedDependenciesList
                |
                |Please add them in the API-dependencies and rerun the build.
            """.trimMargin()
        }
    }

    @TaskAction
    override fun compile() {
        validatedExportedLibraries()
        super.compile()
    }

    companion object {
        private const val LINK_FROM_SOURCES_PROPERTY = "kotlin.native.linkFromSources"
    }
}

internal class CacheBuilder(val project: Project, val binary: NativeBinary) {

    private val nativeSingleFileResolveStrategy: SingleFileKlibResolveStrategy
        get() = CompilerSingleFileKlibResolveAllowingIrProvidersStrategy(
            listOf(KLIB_INTEROP_IR_PROVIDER_IDENTIFIER)
        )
    private val compilation: KotlinNativeCompilation
        get() = binary.compilation

    private val optimized: Boolean
        get() = binary.optimized

    private val debuggable: Boolean
        get() = binary.debuggable

    private val konanCacheKind: NativeCacheKind
        get() = project.konanCacheKind

    // Inputs and outputs
    private val libraries: FileCollection
        get() = compilation.compileDependencyFiles.filterOutPublishableInteropLibs(project)

    private val target: String
        get() = compilation.konanTarget.name

    private val rootCacheDirectory by lazy {
        getRootCacheDirectory(File(project.konanHome), compilation.konanTarget, debuggable, konanCacheKind)
    }

    private fun getAllDependencies(dependency: ResolvedDependency): Set<ResolvedDependency> {
        val allDependencies = mutableSetOf<ResolvedDependency>()

        fun traverseAllDependencies(dependency: ResolvedDependency) {
            if (dependency in allDependencies)
                return
            allDependencies.add(dependency)
            dependency.children.forEach { traverseAllDependencies(it) }
        }

        dependency.children.forEach { traverseAllDependencies(it) }
        return allDependencies
    }

    private fun ByteArray.toHexString() = joinToString("") { (0xFF and it.toInt()).toString(16).padStart(2, '0') }

    private fun computeDependenciesHash(dependency: ResolvedDependency): String {
        val allArtifactsPaths =
            (dependency.moduleArtifacts + getAllDependencies(dependency).flatMap { it.moduleArtifacts })
                .map { it.file.absolutePath }
                .distinct()
                .sortedBy { it }
                .joinToString("|") { it }
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(allArtifactsPaths.toByteArray(StandardCharsets.UTF_8))
        return hash.toHexString()
    }

    private fun getCacheDirectory(dependency: ResolvedDependency): File {
        val moduleCacheDirectory = File(rootCacheDirectory, dependency.moduleName)
        val versionCacheDirectory = File(moduleCacheDirectory, dependency.moduleVersion)
        return File(versionCacheDirectory, computeDependenciesHash(dependency))
    }

    private fun needCache(libraryPath: String) =
        libraryPath.startsWith(project.gradle.gradleUserHomeDir.absolutePath) && libraryPath.endsWith(".klib")

    private fun ensureDependencyPrecached(dependency: ResolvedDependency, visitedDependencies: MutableSet<ResolvedDependency>) {
        if (dependency in visitedDependencies)
            return
        visitedDependencies += dependency
        dependency.children.forEach { ensureDependencyPrecached(it, visitedDependencies) }

        val artifactsToAddToCache = dependency.moduleArtifacts.filter { needCache(it.file.absolutePath) }
        if (artifactsToAddToCache.isEmpty()) return

        val dependenciesCacheDirectories = getAllDependencies(dependency)
            .map { childDependency ->
                val hasKlibs = childDependency.moduleArtifacts.any { it.file.absolutePath.endsWith(".klib") }
                val cacheDirectory = getCacheDirectory(childDependency)
                // We can only compile klib to cache if all of its dependencies are also cached.
                if (hasKlibs && !cacheDirectory.exists())
                    return
                cacheDirectory
            }
            .filter { it.exists() }
        val cacheDirectory = getCacheDirectory(dependency)
        cacheDirectory.mkdirs()

        val artifactsLibraries = artifactsToAddToCache
            .map {
                resolveSingleFileKlib(
                    KFile(it.file.absolutePath),
                    logger = GradleLoggerAdapter(project.logger),
                    strategy = nativeSingleFileResolveStrategy
                )
            }
            .associateBy { it.uniqueName }

        // Top sort artifacts.
        val sortedLibraries = mutableListOf<KotlinLibrary>()
        val visitedLibraries = mutableSetOf<KotlinLibrary>()

        fun dfs(library: KotlinLibrary) {
            visitedLibraries += library
            library.unresolvedDependencies
                .map { artifactsLibraries[it.path] }
                .forEach {
                    if (it != null && it !in visitedLibraries)
                        dfs(it)
                }
            sortedLibraries += library
        }

        for (library in artifactsLibraries.values)
            if (library !in visitedLibraries)
                dfs(library)

        for (library in sortedLibraries) {
            if (File(cacheDirectory, library.uniqueName.cachedName).exists())
                continue
            project.logger.info("Compiling ${library.uniqueName} to cache")
            val args = mutableListOf(
                "-p", konanCacheKind.produce!!,
                "-target", target
            )
            if (debuggable)
                args += "-g"
            args += "-Xadd-cache=${library.libraryFile.absolutePath}"
            args += "-Xcache-directory=${cacheDirectory.absolutePath}"
            args += "-Xcache-directory=${rootCacheDirectory.absolutePath}"

            dependenciesCacheDirectories.forEach {
                args += "-Xcache-directory=${it.absolutePath}"
            }
            getAllDependencies(dependency)
                .flatMap { it.moduleArtifacts }
                .map { it.file }
                .filterKlibsPassedToCompiler(project)
                .forEach {
                    args += "-l"
                    args += it.absolutePath
                }
            library.unresolvedDependencies
                .mapNotNull { artifactsLibraries[it.path] }
                .forEach {
                    args += "-l"
                    args += it.libraryFile.absolutePath
                }
            KotlinNativeCompilerRunner(project).run(args)
        }
    }

    private val String.cachedName
        get() = getCacheFileName(this, konanCacheKind, compilation.konanTarget)

    private fun ensureCompilerProvidedLibPrecached(
        platformLibName: String,
        platformLibs: Map<String, File>,
        visitedLibs: MutableSet<String>
    ) {
        if (platformLibName in visitedLibs)
            return
        visitedLibs += platformLibName
        val platformLib = platformLibs[platformLibName] ?: error("$platformLibName is not found in platform libs")
        if (File(rootCacheDirectory, platformLibName.cachedName).exists())
            return
        val unresolvedDependencies = resolveSingleFileKlib(
            KFile(platformLib.absolutePath),
            logger = GradleLoggerAdapter(project.logger),
            strategy = nativeSingleFileResolveStrategy
        ).unresolvedDependencies
        for (dependency in unresolvedDependencies)
            ensureCompilerProvidedLibPrecached(dependency.path, platformLibs, visitedLibs)
        project.logger.info("Compiling $platformLibName (${visitedLibs.size}/${platformLibs.size}) to cache")
        val args = mutableListOf(
            "-p", konanCacheKind.produce!!,
            "-target", target
        )
        if (debuggable)
            args += "-g"
        args += "-Xadd-cache=${platformLib.absolutePath}"
        args += "-Xcache-directory=${rootCacheDirectory.absolutePath}"
        KotlinNativeCompilerRunner(project).run(args)
    }

    private fun ensureCompilerProvidedLibsPrecached() {
        val platformLibs = libraries.filter { it.providedByCompiler(project) }.associateBy { it.name }
        val visitedLibs = mutableSetOf<String>()
        for (platformLibName in platformLibs.keys)
            ensureCompilerProvidedLibPrecached(platformLibName, platformLibs, visitedLibs)
    }


    fun buildCompilerArgs(): List<String> = mutableListOf<String>().apply {
        if (konanCacheKind != NativeCacheKind.NONE && !optimized && cacheWorksFor(compilation.konanTarget)) {
            rootCacheDirectory.mkdirs()
            ensureCompilerProvidedLibsPrecached()
            add("-Xcache-directory=${rootCacheDirectory.absolutePath}")
            val visitedDependencies = mutableSetOf<ResolvedDependency>()
            val allCacheDirectories = mutableSetOf<String>()
            val compileDependencyConfiguration = project.configurations.getByName(compilation.compileDependencyConfigurationName)
            for (root in compileDependencyConfiguration.resolvedConfiguration.firstLevelModuleDependencies) {
                ensureDependencyPrecached(root, visitedDependencies)
                for (dependency in listOf(root) + getAllDependencies(root)) {
                    val cacheDirectory = getCacheDirectory(dependency)
                    if (cacheDirectory.exists())
                        allCacheDirectories += cacheDirectory.absolutePath
                }
            }
            for (cacheDirectory in allCacheDirectories)
                add("-Xcache-directory=$cacheDirectory")
        }
    }

    private class GradleLoggerAdapter(private val gradleLogger: Logger) : KLogger {
        override fun log(message: String) = gradleLogger.info(message)
        override fun warning(message: String) = gradleLogger.warn(message)
        override fun error(message: String) = kotlin.error(message)
        override fun fatal(message: String): Nothing = kotlin.error(message)
    }

    companion object {
        internal fun getRootCacheDirectory(konanHome: File, target: KonanTarget, debuggable: Boolean, cacheKind: NativeCacheKind): File {
            require(cacheKind != NativeCacheKind.NONE) { "Usupported cache kind: ${NativeCacheKind.NONE}" }
            val optionsAwareCacheName = "$target${if (debuggable) "-g" else ""}$cacheKind"
            return konanHome.resolve("klib/cache/$optionsAwareCacheName")
        }

        internal fun getCacheFileName(baseName: String, cacheKind: NativeCacheKind, konanTarget: KonanTarget): String =
            cacheKind.outputKind?.let {
                "${it.prefix(konanTarget)}${baseName}-cache${it.suffix(konanTarget)}"
            } ?: error("No output for kind $cacheKind")

        internal fun cacheWorksFor(target: KonanTarget) =
            target == KonanTarget.IOS_X64 || target == KonanTarget.MACOS_X64

        internal val DEFAULT_CACHE_KIND: NativeCacheKind = NativeCacheKind.STATIC
    }
}

open class CInteropProcess : DefaultTask() {

    @Internal
    lateinit var settings: DefaultCInteropSettings

    @Internal // Taken into account in the outputFileProvider property
    lateinit var destinationDir: Provider<File>

    val konanTarget: KonanTarget
        @Internal get() = settings.compilation.konanTarget

    val interopName: String
        @Internal get() = settings.name

    val baseKlibName: String
        @Internal get() {
            val compilationPrefix = settings.compilation.let {
                if (it.isMainCompilation) project.name else it.name
            }
            return "$compilationPrefix-cinterop-$interopName"
        }

    val outputFileName: String
        @Internal get() = with(CompilerOutputKind.LIBRARY) {
            "$baseKlibName${suffix(konanTarget)}"
        }

    val moduleName: String
        @Input get() = project.klibModuleName(baseKlibName)

    @get:Internal
    val outputFile: File
        get() = outputFileProvider.get()

    // Inputs and outputs.

    @OutputFile
    val outputFileProvider: Provider<File> =
        project.provider { destinationDir.get().resolve(outputFileName) }

    val defFile: File
        @InputFile get() = settings.defFile

    val packageName: String?
        @Optional @Input get() = settings.packageName

    val compilerOpts: List<String>
        @Input get() = settings.compilerOpts

    val linkerOpts: List<String>
        @Input get() = settings.linkerOpts

    val headers: FileCollection
        @InputFiles get() = settings.headers

    val allHeadersDirs: Set<File>
        @Input get() = settings.includeDirs.allHeadersDirs.files

    val headerFilterDirs: Set<File>
        @Input get() = settings.includeDirs.headerFilterDirs.files

    val libraries: FileCollection
        @InputFiles get() = settings.dependencyFiles.filterOutPublishableInteropLibs(project)

    val extraOpts: List<String>
        @Input get() = settings.extraOpts

    val kotlinNativeVersion: String
        @Input get() = project.konanVersion.toString()

    // Task action.
    @TaskAction
    fun processInterop() {
        val args = mutableListOf<String>().apply {
            addArg("-o", outputFile.absolutePath)

            addArgIfNotNull("-target", konanTarget.visibleName)
            addArgIfNotNull("-def", defFile.canonicalPath)
            addArgIfNotNull("-pkg", packageName)

            addFileArgs("-header", headers)

            compilerOpts.forEach {
                addArg("-compiler-option", it)
            }

            linkerOpts.forEach {
                addArg("-linker-option", it)
            }

            libraries.files.filterKlibsPassedToCompiler(project).forEach { library ->
                addArg("-library", library.absolutePath)
            }

            addArgs("-compiler-option", allHeadersDirs.map { "-I${it.absolutePath}" })
            addArgs("-headerFilterAdditionalSearchPrefix", headerFilterDirs.map { it.absolutePath })

            if (project.konanVersion.isAtLeast(1, 4, 0)) {
                addArg("-Xmodule-name", moduleName)
            }

            addAll(extraOpts)
        }

        outputFile.parentFile.mkdirs()
        KotlinNativeCInteropRunner(project).run(args)
    }
}
