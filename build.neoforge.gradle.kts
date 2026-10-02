plugins {
    id("net.neoforged.moddev") version "2.0.148"
    id("neoforge-mutex")
}

version = "${property("mod.version")}+${sc.current.project.substringBefore('-')}"
base.archivesName = "${property("mod.id")}-neoforge"

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

neoForge {
    version = property("deps.neo_loader") as String

    mods {
        register(property("mod.id") as String) {
            sourceSet(sourceSets["main"])
        }
    }

    runs {
        register("client") {
            client()
            gameDirectory = rootProject.file("run/${sc.current.project}")
            if (project.hasProperty("demo")) {
                systemProperty("worldmap.demo", project.property("demo") as String)
                programArguments.addAll("--quickPlaySingleplayer", "New World")
            }
        }
        register("server") {
            server()
            gameDirectory = rootProject.file("run/${sc.current.project}-server")
        }
    }
}

// Точки входа других лоадеров не компилируем.
sourceSets.main { java.exclude("**/platform/fabric/**", "**/platform/forge/**") }

java {
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava
    toolchain.languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
}

tasks {
    withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }

    processResources {
        val props = mapOf(
            "id" to sc.properties["mod.id"],
            "name" to sc.properties["mod.name"],
            "version" to project.version.toString(),
            "minecraft" to sc.properties["mod.mc_compat"],
        )
        inputs.properties(props)
        // До 20.5 NeoForge читал META-INF/mods.toml.
        filesMatching(listOf("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) { expand(props) }
        exclude("fabric.mod.json", "pack.mcmeta")
        if (sc.current.parsed >= "1.20.5") exclude("META-INF/mods.toml")
        else exclude("META-INF/neoforge.mods.toml")
    }

    named("createMinecraftArtifacts") {
        dependsOn("stonecutterGenerate")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        from(jar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/${property("mod.version")}"))
    }
}
