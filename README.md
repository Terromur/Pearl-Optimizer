# Pearl Optimizer

**Vulkan renderer optimizer for Minecraft · Fabric**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.3-3C8527?style=flat-square)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/Fabric_Loader-0.20.1+-DBB69B?style=flat-square)](https://fabricmc.net/)
[![Version](https://img.shields.io/badge/version-1.0.0-4d6bfe?style=flat-square)]()

A client-side Minecraft mod that reduces stutters and speeds up world loading. Fully compatible with any mods.

---

## Metrics

| Metric | Vanilla 26.3 | With Pearl Optimizer |
|---|---|---|
| First world enter | 1864 ms | **651 ms** (−65 %) |
| Shader compilation (warm start) | 1103 ms | **35 ms** |
| Vulkan object reuse | — | **94 %** (created 170 / reused 2570) |
| Pipeline cache | no disk | ~**267 KB** between launches |
| Shader cache | no disk | ~**3.9-4.9 MB** between launches |

Average FPS in a stable scene changes almost not at all — the effect is in **fewer stutters when entering a world, pressing F3+T, and changing dimensions**, as well as in smoother frametimes (higher 1 % low). The main FPS on a weak GPU comes from Sodium — this mod **does not replace it, but complements it**. Optionally, you can change FIFO to MAILBOX, which can theoretically increase FPS, reduce latency even further, and even improve mouse and keyboard responsiveness.

---

## Features

- **On-disk SPIR-V shader cache** — GLSL→SPIR-V is compiled once, then loaded from disk (key — SHA-256 of the source + include files).
- **In-memory SPIR-V cache** — LRU deduplication in RAM, 128 MB budget, prefetch at startup.
- **Shared `VkPipelineCache`** — instead of `VK_NULL_HANDLE` in vanilla 26.3, one cache per device.
- **Persistent pipeline cache** — serialization to disk and seeding via `pInitialData` on the next launch.
- **Vulkan object duplicate cache** — refcount registry of `VkShaderModule` / `VkDescriptorSetLayout` / `VkPipelineLayout`.
- **Dynamic GPU buffer presizing** — 128 blocks instead of 1–2, removing the cascade of ~9 resizes during world load.
- **shaderc compilation optimization** — `PERFORMANCE` + debug info with a runtime fallback probe.
- **Single-flight compilation** — parallel MISSes of the same key share one compilation.
- **Present mode override** — optional substitution of FIFO → `MAILBOX` to reduce input lag.
- **Clean teardown** — `Device close flush` without `VUID-vkDestroyDevice` errors.

---

## ⚙️ Configuration

The `config/pearl-optimizer.json` file is created automatically on first launch. A missing field = default, old configs are read without edits.

```json
{
  "bumpInitialCapacity": true,
  "initialCapacityBlocks": 128,
  "sharedPipelineCache": true,
  "persistentPipelineCache": true,
  "objectCache": true,
  "presentMode": "vanilla",
  "shaderCache": true,
  "spirvMemoryCache": true,
  "purgeThrottle": true,
  "asyncStore": true,
  "inflightDedup": true,
  "shaderOptimization": true,
  "shaderOptimizationAllowElimination": false,
  "shaderCacheMaxSizeMb": 64,
  "verboseLog": false
}
```

Any field can be overridden with a JVM property:

```
# Verbose module report + HIT/MISS
java -Dpearloptimizer.log=true -jar minecraft.jar

# A/B run: everything at defaults, but without the on-disk shader cache
java -Dpearloptimizer.shadercache=false -jar minecraft.jar

# MAILBOX + extended cache limit
java -Dpearloptimizer.presentmode=mailbox -Dpearloptimizer.cachemaxmb=256 -jar minecraft.jar
```

The full list of properties — `pearloptimizer.buffers`, `.capacity`, `.pipelinecache`, `.pipecachedisk`, `.objcache`, `.presentmode`, `.shadercache`, `.spirvmemory`, `.purgethrottle`, `.asyncstore`, `.inflightdedup`, `.shadopt`, `.shadoptelim`, `.cachemaxmb`, `.log`.

---

## 🧪 Diagnostics

The mod has detailed diagnostics that are disabled by default. If problems occur, first of all enable the detailed report:

```
-Dpearloptimizer.log=true
```

It prints HIT/MISS lines, barrier census, store health report, and `PURGE REPORT LINE (T-C3)`. Warnings are printed always.

---

