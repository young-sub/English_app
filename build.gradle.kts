import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm") version "1.9.24" apply false
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}

subprojects {
    if (name != "app") {
        apply(plugin = "org.jetbrains.kotlin.jvm")
    }

    if (name != "app") {
        dependencies {
            add("testImplementation", kotlin("test"))
            add("testImplementation", "org.junit.jupiter:junit-jupiter:5.10.2")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            maxHeapSize = "256m"
            jvmArgs(
                "-Xms128m",
                "-Xss256k",
                "-XX:ActiveProcessorCount=2",
                "-XX:ParallelGCThreads=1",
                "-XX:ConcGCThreads=1",
            )
            maxParallelForks = 1
        }
    }
}
