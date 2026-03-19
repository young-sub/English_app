import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "english_app"

val repoLayout = Properties().apply {
    val layoutFile = file("gradle/repo-layout.properties")
    if (layoutFile.exists()) {
        layoutFile.inputStream().use(::load)
    }
}

fun moduleDir(moduleName: String, defaultPath: String): java.io.File {
    val configuredPath = repoLayout.getProperty("module.$moduleName", defaultPath)
    return file(configuredPath)
}

include("core-contracts")
project(":core-contracts").projectDir = moduleDir("core-contracts", "components/core-contracts")

include("f1-provisioning")
project(":f1-provisioning").projectDir = moduleDir("f1-provisioning", "components/f1-provisioning")

include("f2-camera-ocr")
project(":f2-camera-ocr").projectDir = moduleDir("f2-camera-ocr", "components/f2-camera-ocr")

include("f3-text-postprocess")
project(":f3-text-postprocess").projectDir = moduleDir("f3-text-postprocess", "components/f3-text-postprocess")

include("f4-dictionary")
project(":f4-dictionary").projectDir = moduleDir("f4-dictionary", "components/f4-dictionary")

include("f5-tts")
project(":f5-tts").projectDir = moduleDir("f5-tts", "components/f5-tts")

include("f6-performance")
project(":f6-performance").projectDir = moduleDir("f6-performance", "components/f6-performance")

include("integration-tests")
project(":integration-tests").projectDir = moduleDir("integration-tests", "modules/integration/tests")
include("app")
project(":app").projectDir = moduleDir("app", "app")
