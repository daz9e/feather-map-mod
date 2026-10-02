plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.20.1-fabric"

stonecutter parameters {
    val (_, loader) = current.project.split('-', limit = 2)

    properties {
        tags(current.version, loader)
    }

    // //? if fabric / neoforge / forge
    constants {
        match(loader, "fabric", "neoforge", "forge")
    }

    replacements {
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
        }
        // 26.1: рендер интерфейса — «извлечение состояния».
        string(current.parsed >= "26.1") {
            replace("GuiGraphics", "GuiGraphicsExtractor")
            replace("net.minecraft.client.renderer.block.model.BakedQuad", "net.minecraft.client.resources.model.geometry.BakedQuad")
        }
    }
}

// Собрать все версии и сложить jar-ы в build/libs/<версия мода>/.
tasks.register("buildAll") {
    group = "build"
    dependsOn(stonecutter.versions.map { ":${it.project}:buildAndCollect" })
}
