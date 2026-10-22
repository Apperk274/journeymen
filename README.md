# Journeymen

A Minecraft 1.21.1 mod for NeoForge and Fabric. Summon a journeyman, hand them a schematic, materials and tools, and they build it block by block, the way a player would.

Status: project skeleton only (phase 0). See `CLAUDE.md` for the design and roadmap.

## Requirements

- [mise](https://mise.jdx.dev/), which provides Java 21 from `mise.toml`
- Everything else (Gradle, Minecraft, the loaders) is downloaded by the Gradle wrapper

```sh
mise trust
mise install
```

## Building and running

Run Gradle through mise so it picks up Java 21:

```sh
mise exec -- ./gradlew build                   # jars for both loaders
mise exec -- ./gradlew :neoforge:runClient     # dev client on NeoForge
mise exec -- ./gradlew :fabric:runClient       # dev client on Fabric
mise exec -- ./gradlew :neoforge:runData       # data generation (NeoForge)
```

Built jars land in `neoforge/build/libs/` and `fabric/build/libs/`. Use the one without `-sources` in the name.

The first build downloads and decompiles Minecraft for each loader and takes several minutes. Later builds take seconds.

## Layout

| Module | Contents |
|---|---|
| `common/` | Shared code. Compiled against vanilla Minecraft only, so it cannot import NeoForge or Fabric classes. Most of the mod lives here. |
| `neoforge/` | NeoForge entry point (`JourneymenNeoForge`) and NeoForge-specific wiring. |
| `fabric/` | Fabric entry point (`JourneymenFabric`) and Fabric-specific wiring. |
| `buildSrc/` | Gradle conventions shared by the modules. |

Common code reaches loader features through interfaces in `common/.../platform/services`. Each loader provides an implementation, registered in `META-INF/services/` in that loader's resources.

Based on [MultiLoader-Template](https://github.com/jaredlll08/MultiLoader-Template) (1.21.1 branch).

## Editor

Open the folder in VS Code and install the recommended extensions (Java Extension Pack, Gradle for Java). `.vscode/settings.json` points VS Code at the mise-installed JDK. It's machine-specific and not committed, so on another machine set `java.import.gradle.java.home` to your own JDK 21 path.

IntelliJ IDEA with the Minecraft Development plugin also works and generates run configurations automatically.
