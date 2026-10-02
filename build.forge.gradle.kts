plugins {
    id("net.neoforged.moddev.legacyforge") version "2.0.148"
}

version = "${property("mod.version")}+${sc.current.project.substringBefore('-')}"
base.archivesName = "${property("mod.id")}-forge"

legacyForge {
    version = property("deps.forge") as String

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
sourceSets.main { java.exclude("**/platform/fabric/**", "**/platform/neoforge/**") }

java {
    targetCompatibility = JavaVersion.VERSION_17
    sourceCompatibility = JavaVersion.VERSION_17
    toolchain.languageVersion = JavaLanguageVersion.of(17)
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
        filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) { expand(props) }
        exclude("fabric.mod.json", "META-INF/neoforge.mods.toml")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        // Для Forge нужен jar с SRG-именами.
        from(named<Jar>("reobfJar").flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/${project.property("mod.version")}"))
    }
}
