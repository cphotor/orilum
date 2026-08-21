import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.folioepub"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.folioepub"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX 基础
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room（本地数据库）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WebView 资源走 https 虚拟域，满足 ES Module / fetch
    implementation(libs.androidx.webkit)
    // SAF 目录树遍历（指定字体目录导入）
    implementation(libs.androidx.documentfile)

    // 单元测试
    testImplementation(libs.junit)
    // 真实 org.json：本地单测用 android.jar 的打桩 org.json 会抛 not mocked，
    // 故在 test classpath 显式引入真实实现（不影响主 Release 运行时性能）。
    testImplementation(libs.org.json)
}

/** foliate-js 版本号（改动升级时更新此处即可）。 */
val FOLIATE_JS_VERSION = "1.0.1"

/** 校验：构建脚本里注册的自定义任务是否可见。 */
tasks.register("pingFoliate") {
    doLast { println("PING FOLIATE OK") }
}

/** tarball 缓存产物位置。 */
val foliateJsTarball = layout.buildDirectory.file("foliate-js/cache/foliate-js-${FOLIATE_JS_VERSION}.tgz")

/** 下载官方 tarball（幂等：已存在则跳过）。 */
val downloadFoliateJs by tasks.registering {
    outputs.file(foliateJsTarball)
    doLast {
        val file = foliateJsTarball.get().asFile
        if (!file.exists()) {
            file.parentFile.mkdirs()
            val url = URI(
                "https://registry.npmmirror.com/foliate-js/-/foliate-js-${FOLIATE_JS_VERSION}.tgz",
            ).toURL()
            logger.lifecycle("正在下载 foliate-js ${FOLIATE_JS_VERSION} ...")
            url.openStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        }
    }
}

/** 解压 tarball，复制全部 JS 模块到 assets/foliate-js。 */
val fetchFoliateJs by tasks.registering(Copy::class) {
    dependsOn(downloadFoliateJs)
    // :app 的 projectDirectory 即 app/，故 assets 是 src/main/assets
    val outputDir = project.layout.projectDirectory.dir("src/main/assets/foliate-js")
    outputs.dir(outputDir)
    inputs.property("version", FOLIATE_JS_VERSION)
    from(tarTree(resources.gzip(foliateJsTarball))) {
        include("package/**/*.js")
        eachFile {
            // tar 顶层是 package/；去掉前缀，落到 assets/foliate-js/
            path = relativePath.pathString.removePrefix("package/")
        }
        includeEmptyDirs = false
    }
    into(outputDir)
}

// 构建 APK 前确保 foliate-js 就位。
// AGP 在脚本求值后才注册 merge*Assets 任务，故放到 afterEvaluate 延迟绑定任务引用。
afterEvaluate {
    tasks.named("mergeDebugAssets") { dependsOn(fetchFoliateJs) }
    tasks.named("mergeReleaseAssets") { dependsOn(fetchFoliateJs) }
}

/**
 * 把 sample-epub-src/ 打包成合法的 EPUB3 zip 到 assets/sample/sample.epub。
 * 遵循 EPUB 规范：mimetype 首条目且 STORED(不压缩)。
 */
val sampleSrcDir = project.layout.projectDirectory.dir("sample-epub-src")
val sampleEpubFile = project.layout.projectDirectory.file("src/main/assets/sample/sample.epub")

val makeSampleEpub by tasks.registering {
    inputs.dir(sampleSrcDir)
    outputs.file(sampleEpubFile)
    doLast {
        val baseFile = sampleSrcDir.asFile
        val files = sampleSrcDir.asFile.walkTopDown()
            .filter { it.isFile }
            .sortedWith(compareBy({ it.name != "mimetype" }, { it.toRelativeString(baseFile) }))
            .toList()
        val out = sampleEpubFile.asFile
        out.parentFile.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            files.forEach { f ->
                val rel = f.toRelativeString(baseFile)
                val entry = ZipEntry(rel.replace(File.separatorChar, '/'))
                if (rel == "mimetype") {
                    entry.method = ZipEntry.STORED
                    val size = f.length()
                    entry.size = size
                    entry.compressedSize = size
                    entry.crc = CRC32().let { crc ->
                        f.inputStream().use { input ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                crc.update(buf, 0, n)
                            }
                        }
                        crc.value
                    }
                } else {
                    entry.method = ZipEntry.DEFLATED
                }
                zos.putNextEntry(entry)
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        logger.lifecycle("已生成示例书: ${sampleEpubFile.asFile}")
    }
}

afterEvaluate {
    tasks.named("mergeDebugAssets") { dependsOn(makeSampleEpub) }
    tasks.named("mergeReleaseAssets") { dependsOn(makeSampleEpub) }
}