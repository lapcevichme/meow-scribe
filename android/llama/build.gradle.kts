import java.net.URI

plugins {
    alias(libs.plugins.android.library)
}

/** Тег llama.cpp. Исходники скачиваются один раз в third_party/ (в git их нет). */
val llamaCppTag = "b12185"
val llamaCppDir = layout.projectDirectory.dir("third_party/llama.cpp")

android {
    namespace = "com.lapcevichme.meowscribe.llama"
    compileSdk {
        version = release(37)
    }
    // NDK r28+ по умолчанию выравнивает .so под страницы 16 КБ — без этого не грузится на новых образах.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 28
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                // ggml в Debug в десятки раз медленнее: нативную часть всегда собираем с оптимизациями.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17")
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

/** Скачивает исходники llama.cpp, если их ещё нет. Совместима с configuration cache: без обращений к project. */
abstract class FetchLlamaCpp : DefaultTask() {
    @get:Inject abstract val fs: FileSystemOperations
    @get:Inject abstract val archives: ArchiveOperations
    @get:Input abstract val tag: Property<String>
    @get:OutputDirectory abstract val target: DirectoryProperty

    @TaskAction
    fun fetch() {
        val dir = target.get().asFile
        if (File(dir, "CMakeLists.txt").exists()) return
        val archive = File(temporaryDir, "llama.cpp-${tag.get()}.tar.gz")
        URI("https://github.com/ggml-org/llama.cpp/archive/refs/tags/${tag.get()}.tar.gz").toURL()
            .openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        fs.copy {
            from(archives.tarTree(archives.gzip(archive)))
            into(dir)
            includeEmptyDirs = false
            eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
        }
    }
}

val fetchLlamaCpp = tasks.register<FetchLlamaCpp>("fetchLlamaCpp") {
    tag = llamaCppTag
    target = llamaCppDir
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("generateJsonModel") }
    .configureEach { dependsOn(fetchLlamaCpp) }
