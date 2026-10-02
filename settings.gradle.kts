pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    // Нужный вариант Loom для обфусцированных (≤1.21.11) и необфусцированных (26.1+) версий.
    id("dev.kikugie.loom-back-compat") version "0.4.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // Узлы versions/{mc}-{loader}; у каждого лоадера свой build.{loader}.gradle.kts.
        fun match(project: String, vararg loaders: String, version: String = project) {
            for (loader in loaders) version("$project-$loader", version).buildscript("build.$loader.gradle.kts")
        }

        // На 1.20.1 вместо NeoForge — Forge 47 (его же грузит NeoForge 47.1).
        match("1.20.1", "fabric", "forge")
        match("1.20.4", "fabric", "neoforge")
        match("1.20.6", "fabric", "neoforge")
        match("1.21.1", "fabric", "neoforge")
        match("1.21.3", "fabric", "neoforge")
        match("1.21.4", "fabric", "neoforge")
        match("1.21.5", "fabric", "neoforge")
        match("1.21.8", "fabric", "neoforge")
        match("1.21.10", "fabric", "neoforge")
        match("1.21.11", "fabric", "neoforge")
        match("26.1", "fabric", "neoforge", version = "26.1.2")
        match("26.2", "fabric", "neoforge")
        match("26.3", "fabric", "neoforge")
        vcsVersion = "1.20.1-fabric"
    }
}

rootProject.name = "worldmap"
