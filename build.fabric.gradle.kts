plugins {
    // Подключает нужный вариант Loom в зависимости от версии Minecraft.
    id("dev.kikugie.loom-back-compat")
}

version = "${property("mod.version")}+${sc.current.project.substringBefore('-')}"
base.archivesName = "${property("mod.id")}-fabric"

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${sc.properties.get<String>("deps.fabric_api")}")
}

loom {
    runConfigs.all {
        runDirectory = rootProject.file("run/${sc.current.project}")
        // -Pdemo=sp — демо-сценарий со скриншотами (см. Demo.java).
        if (project.hasProperty("demo")) {
            vmArg("-Dworldmap.demo=${project.property("demo")}")
            programArgs("--quickPlaySingleplayer", "New World")
        }
    }
}

// Точки входа других лоадеров не компилируем.
sourceSets.main { java.exclude("**/platform/neoforge/**", "**/platform/forge/**") }

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
            "loader" to (sc.properties.getOrNull<String>("mod.fabric_loader_min") ?: "0.15.0"),
        )
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
        exclude("META-INF/neoforge.mods.toml", "META-INF/mods.toml", "pack.mcmeta")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/${project.property("mod.version")}"))
    }
}
