import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.aboutlibraries.android)
}

// In-app license list (Settings → Privacy & about → Open-source licenses), generated from the resolved dependencies
// including transitive ones, with full license texts. Overrides for proprietary Google components: config/aboutlibraries.
aboutLibraries {
  collect {
    configPath.set(rootProject.layout.projectDirectory.dir("config/aboutlibraries"))
  }
  export {
    prettyPrint.set(true)
  }
}

val versionProps = Properties().apply {
  load(rootProject.file("version.properties").inputStream())
}

android {
  namespace = "de.juliandigitalproducts.notitocal"
  compileSdk = 37

  defaultConfig {
    applicationId = "de.juliandigitalproducts.notitocal"
    minSdk = 31
    targetSdk = 37
    // Single source for local and CI builds (C26): version.properties.
    versionCode = versionProps.getProperty("versionCode").toInt()
    versionName = versionProps.getProperty("versionName")
    // -PnotitocalAllAbis keeps every ABI (CI emulator smoke test on x86_64).
    if (!project.hasProperty("notitocalAllAbis")) ndk { abiFilters += listOf("arm64-v8a") }
    vectorDrawables.useSupportLibrary = true
    // Instrumented E2E tests (app/src/androidTest), run on an emulator or device: ./gradlew :app:connectedDebugAndroidTest
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Robolectric tests run the real pipeline, which needs the app's strings.
  testOptions.unitTests.isIncludeAndroidResources = true

  // Release signing: notitocal-release.keystore.properties or NOTITOCAL_KEYSTORE_* env vars.
  // Without a release key the release build stops (as in EV Trigger Flows). The public preview key in signing/ is only
  // used when asked for explicitly with -PnotitocalPreview (CI smoke test, forks); such builds are never published.
  val keystorePropertiesFile = rootProject.file("../notitocal-release.keystore.properties").takeIf { it.exists() }
    ?: rootProject.file("notitocal-release.keystore.properties").takeIf { it.exists() }
  val keystoreProps = Properties().apply {
    if (keystorePropertiesFile != null) {
      load(keystorePropertiesFile.inputStream())
    }
  }

  val releaseStorePath = System.getenv("NOTITOCAL_KEYSTORE_FILE")
    ?: keystoreProps.getProperty("storeFile")
    ?: rootProject.file("../notitocal-release.jks").takeIf { it.exists() }?.absolutePath
  val releaseStorePassword = System.getenv("NOTITOCAL_KEYSTORE_PASSWORD")
    ?: keystoreProps.getProperty("storePassword")
  val releaseKeyAlias = System.getenv("NOTITOCAL_KEY_ALIAS")
    ?: keystoreProps.getProperty("keyAlias")
    ?: "notitocal"
  val releaseKeyPassword = System.getenv("NOTITOCAL_KEY_PASSWORD")
    ?: keystoreProps.getProperty("keyPassword")
    ?: releaseStorePassword

  val isReleaseConfigured = !releaseStorePath.isNullOrBlank() && !releaseStorePassword.isNullOrBlank()
  val previewRequested = project.hasProperty("notitocalPreview")
  if (!isReleaseConfigured && !previewRequested) {
    gradle.taskGraph.whenReady {
      if (allTasks.any { it.project == project && it.name.contains("Release") && (it.name.startsWith("package") || it.name.startsWith("bundle")) }) {
        throw GradleException(
          "No NotiToCal release key: set NOTITOCAL_KEYSTORE_FILE/_PASSWORD/NOTITOCAL_KEY_ALIAS/_PASSWORD " +
            "or ../notitocal-release.keystore.properties. For a test build with the public preview key add -PnotitocalPreview.",
        )
      }
    }
  }

  signingConfigs {
    create("release") {
      if (isReleaseConfigured) {
        storeFile = file(releaseStorePath)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      } else {
        storeFile = rootProject.file("signing/preview.keystore")
        storePassword = "notitocal-preview"
        keyAlias = "preview"
        keyPassword = "notitocal-preview"
      }
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  buildTypes {
    release {
      signingConfig = signingConfigs.getByName("release")
      buildConfigField("boolean", "PREVIEW_SIGNED", (!isReleaseConfigured).toString())
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
    debug {
      buildConfigField("boolean", "PREVIEW_SIGNED", "true")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  androidResources {
    generateLocaleConfig = true
    // Only the app's own languages (libraries ship dozens more that would show up in the per-app language picker).
    localeFilters += listOf("en", "de", "fr", "es", "it", "pt", "pl")
  }
  packaging {
    jniLibs { useLegacyPackaging = false }
  }
  lint {
    abortOnError = true
    // Model / API version strings are not translatable UI text.
    disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    // navigation-runtime's lint check crashes (NoClassDefFoundError) on a clean build; routes are plain strings here.
    disable += listOf("WrongNavigateRouteType")
    // Only arm64-v8a is shipped on purpose (on-device model runtime, APK size); x86 ChromeOS is not a target.
    disable += listOf("ChromeOsAbiSupport")
    // Full photo access is wanted: new screenshots in the chosen folders are analysed automatically and quick-capture
    // photos right away; "selected photos only" would miss every new screenshot (see the manifest comment).
    disable += listOf("SelectedPhotoAccess")
  }
}

kotlin {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.splashscreen)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.mediapipe.tasks.genai)
  implementation(libs.mlkit.text.recognition)
  implementation(libs.aboutlibraries.compose.m3)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.uiautomator)
  debugImplementation(libs.androidx.ui.tooling)
}

// Pipeline harness: the sample messages through the whole local pipeline with fake CalDAV/server/webhook sinks and a
// fixed clock (PipelineHarnessTest), plus the rules-only sample table (PrefilterSamplesTest).
// ./gradlew :app:pipelineHarness  →  app/build/reports/pipeline-harness.md, app/build/reports/prefilter-30-samples.md
val harnessRequested = gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "pipelineHarness" }
tasks.withType<Test>().configureEach {
  if (harnessRequested && name == "testDebugUnitTest") {
    filter {
      includeTestsMatching("*.PipelineHarnessTest")
      includeTestsMatching("*.PrefilterSamplesTest")
    }
    outputs.upToDateWhen { false }
  }
}
tasks.register("pipelineHarness") {
  group = "verification"
  description = "Runs the sample messages through the whole local pipeline (fake targets, fixed clock)."
  dependsOn("testDebugUnitTest")
}

// Google's AARs (ML Kit, Play services, ODML) ship the licenses of the components bundled inside them (Abseil,
// TensorFlow Lite, …) as third_party_licenses.json/.txt; AGP drops these files. This task merges them (identical texts
// grouped) into the asset google_third_party_notices.json, shown in the app next to the AboutLibraries list.
abstract class GoogleThirdPartyNotices : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val aars: ConfigurableFileCollection

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  @TaskAction
  fun merge() {
    val byName = sortedMapOf<String, String>(String.CASE_INSENSITIVE_ORDER)
    aars.files.filter { it.name.endsWith(".aar") }.sortedBy { it.name }.forEach { aar ->
      ZipFile(aar).use { zip ->
        val index = zip.getEntry("third_party_licenses.json") ?: return@use
        val text = zip.getEntry("third_party_licenses.txt") ?: return@use
        val bytes = zip.getInputStream(text).use { it.readBytes() }
        @Suppress("UNCHECKED_CAST")
        val entries = groovy.json.JsonSlurper().parse(zip.getInputStream(index)) as Map<String, Map<String, Number>>
        entries.forEach { (name, pos) ->
          val start = pos.getValue("start").toInt()
          val length = pos.getValue("length").toInt()
          byName.putIfAbsent(name, String(bytes, start, length, Charsets.UTF_8).trim())
        }
      }
    }
    val grouped = byName.entries.groupBy({ it.value }, { it.key })
      .map { (text, names) -> mapOf("names" to names, "text" to text) }
      .sortedBy { (it["names"] as List<*>).first().toString().lowercase() }
    val dir = outputDir.get().asFile
    dir.mkdirs()
    File(dir, "google_third_party_notices.json").writeText(groovy.json.JsonOutput.toJson(grouped))
  }
}

androidComponents {
  onVariants { variant ->
    val notices = tasks.register<GoogleThirdPartyNotices>("${variant.name}GoogleThirdPartyNotices") {
      aars.from(
        variant.runtimeConfiguration.incoming.artifactView {
          attributes { attribute(Attribute.of("artifactType", String::class.java), "aar") }
          lenient(true)
        }.files,
      )
    }
    variant.sources.assets?.addGeneratedSourceDirectory(notices, GoogleThirdPartyNotices::outputDir)
  }
}
