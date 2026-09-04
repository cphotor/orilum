import org.gradle.api.Action
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.orilum"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.orilum"
        minSdk = 23
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.4"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Release 签名材料由 GitHub Actions 在运行时从 Secrets 解码注入（不入库）。
    // 读取环境变量：KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD。
    // 本地未注入这些变量时，signingConfig 保持为空，release 回退用 debug 签名以便安装调试。
    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // CI 注入密钥时用真实 release 签名；本地无密钥时回退 debug 签名，避免构建失败。
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    // Release 变体产物统一命名为 orilum-<version>.apk，供本地构建、CI artifact、Release 附件三处一致使用。
    applicationVariants.all(object : Action<ApplicationVariant> {
        override fun execute(v: ApplicationVariant) {
            if (v.buildType.name != "release") return
            // all(Action) 触发时变体已配置、outputs 已实现化，可直接遍历改写产物名。
            v.outputs.forEach { output ->
                // outputFileName 在 Kotlin DSL 中是底层实现类的 setter，经 BaseVariantOutputImpl 改写。
                (output as BaseVariantOutputImpl).outputFileName = "orilum-${v.versionName}.apk"
            }
        }
    })
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
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room（本地数据库）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WebView 资源走 https 虚拟域，满足 ES Module / fetch
    implementation(libs.androidx.webkit)

    // 单元测试
    testImplementation(libs.junit)
    // 真实 org.json：本地单测用 android.jar 的打桩 org.json 会抛 not mocked，
    // 故在 test classpath 显式引入真实实现（不影响主 Release 运行时性能）。
    testImplementation(libs.org.json)
}

// 说明：foliate-js 已高度定制（三窗口跨章、翻页吸附、四向独立页边距、分页切分等），
// 已纳入本仓库版本管理（app/src/main/assets/foliate-js/），不再从官方 npm 下载/覆盖。
// 升级 foliate-js 时，直接替换目录内文件并提交即可。