# World Map

> **Experimental.** This is a personal experiment, not a polished release. Expect bugs, rough edges and breaking changes between versions. Back up your worlds.

A parchment-style world map for Minecraft: textured terrain rendering, marks, navigation, and shared marks and teleport on servers.

The mod works in singleplayer and on vanilla servers. When the mod is also installed on the server, you get shared marks, player positions, pings and teleport from the map.

https://github.com/user-attachments/assets/02c9b2dc-bfac-475a-b9f4-75d41ffbee28

- **Textured map** that fills in as you explore, with smooth zoom and panning
- **Marks** with icons and colours, a searchable list, and death marks
- **Compass bar** with guidance to any mark
- **Sharing** marks through chat; on modded servers: shared marks, player positions, pings and teleport

Fabric, NeoForge and Forge, Minecraft 1.20 to 26.3. Fabric requires [Fabric API](https://modrinth.com/mod/fabric-api).

## Controls

- `M`: open the map
- `N`: toggle the compass
- `B`: new mark at your position

## Server

Settings live in `config/worldmap-server.json`. Shared marks are stored in the world (`worldmap_marks.json`). `/worldmap reload` reloads the settings (operator only).

| Option                    | Default | Description                                         |
|---------------------------|---------|-----------------------------------------------------|
| `teleport`                | `op`    | who can teleport: `all`, `op`, `none`               |
| `teleportCooldownSeconds` | `10`    | teleport cooldown (operators are exempt)            |
| `publicMarks`             | `all`   | who can create shared marks: `all`, `op`            |
| `sharePlayerPositions`    | `true`  | show players on the map                             |
| `pings`                   | `true`  | allow pings                                         |
| `maxPublicMarks`          | `500`   | shared marks limit                                  |

## Building

One source tree is built for every version and loader with [Stonecutter](https://stonecutter.kikugie.dev/). Gradle runs on JDK 25. Gradle downloads JDK 17/21 for older versions itself.

```sh
./gradlew buildAll                              # all variants → build/libs/<mod version>/
./gradlew :1.21.1-fabric:build                  # a single variant
./gradlew :1.21.1-fabric:runClient -Pdemo=sp    # demo scenario with screenshots
```

The active version in the sources is `1.20.1-fabric`. Switch it with the `Set active project to <version>` task from the `stonecutter` group, and reset it before committing.

## License

[MIT](LICENSE)
