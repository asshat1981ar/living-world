plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    jvmToolchain(17)
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":game-core"))
                implementation(project(":game-persistence"))
                implementation(project(":game-ai"))
                implementation(project(":game-ui"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
    }
}

// `./gradlew :desktopApp:run --args="30 10"`: headless 30-day simulation runner (INSTRUCTIONS.md).
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the headless simulation inspector."
    val main = kotlin.jvm().compilations.getByName("main")
    dependsOn(main.compileAllTaskName)
    classpath(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("com.livingworld.game.desktop.InspectorMainKt")
    // Relative paths (e.g. --save=build/lw-saves/x.json) resolve from the repo root, as tools/ledger_tools.py expects.
    workingDir = rootProject.projectDir
}
