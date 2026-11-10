# Pearl Optimizer — Configuration Reference

Full reference for `config/pearl-optimizer.json` and the matching `-Dpearloptimizer.*` JVM system properties.

The file is created automatically on first launch with default values. A missing field means "use default", so old configs are read without edits. The mod never overwrites an existing file.

---

## JSON settings reference

| Field | Type | Default | Allowed values | Description |
|---|---|---|---|---|
| `bumpInitialCapacity` | boolean | `true` | `true` / `false` | Enables presizing for the five dynamic GPU storages. When disabled, vanilla growth behavior is used. |
| `initialCapacityBlocks` | integer | `128` | clamp `[0, 65536]` | Initial capacity in blocks for dynamic GPU storages. Vanilla starts at 1–2 blocks. `0` means vanilla behavior. Vanilla growth logic is kept as a fallback if the world grows beyond the presized capacity. |
| `sharedPipelineCache` | boolean | `true` | `true` / `false` | Compiles pipelines through one shared `VkPipelineCache` per device instead of vanilla 26.3's `VK_NULL_HANDLE`. The cache is a child of the device and is destroyed with it. A failed cache creation is cached as `0` and is not retried until the device is reopened. Race handling uses `get → create → putIfAbsent`; the loser destroys its unused cache. |
| `persistentPipelineCache` | boolean | `true` | `true` / `false` | Serializes the shared pipeline cache to disk on device close and seeds it through `pInitialData` on the next launch. Requires `sharedPipelineCache`. Data is validated by device identity (`vendorID`, `deviceID`, `driverVersion`, `pipelineCacheUUID`), SHA-256 payload, Vulkan header, and a meta file. Driver refusal recreates an empty cache; `VK_INCOMPLETE` skips saving; corrupt or foreign files are atomically overwritten after a successful session. Saving happens only in `VulkanDevice.close()` and only if at least one compilation went through the cache. |
| `objectCache` | boolean | `true` | `true` / `false` | Per-device refcount registry for duplicate Vulkan objects. Shader modules are keyed by SHA-256 of the full SPIR-V. Descriptor set layouts are keyed by an ordered list of all bindings with flags; layouts with immutable samplers are not cached. Pipeline layouts are keyed by the descriptor set layout entry ID + full push constant range list + flags. Each `compile()` acquires and each `destroy()` releases; the Vulkan object is destroyed only at refcount `0`. |
| `presentMode` | string | `"vanilla"` | `"vanilla"` / `"mailbox"` | If set to `"mailbox"`, and the game requested FIFO while the surface supports `VK_PRESENT_MODE_MAILBOX_KHR`, the mod substitutes MAILBOX. This avoids a frame queue and reduces input lag. If vsync is off and the game already uses IMMEDIATE, or if MAILBOX is unsupported, the mod does not intervene. Comparison is case-insensitive. |
| `shaderCache` | boolean | `true` | `true` / `false` | Persistent on-disk SPIR-V cache between launches. The cache key is SHA-256 over the shader source, `#define` directives, entry point, shader type, three device-dependent compiler flags, optimization profile, game version, and a fingerprint of all include-file contents. Cache format is `CACHE_FORMAT = 4`. Writes are atomic. Each file is validated by SHA-256 and SPIR-V magic; a corrupt entry is self-healed by recompilation. Any error falls back to vanilla compilation. |
| `spirvMemoryCache` | boolean | `true` | `true` / `false` | In-memory SPIR-V deduplication under the same SHA-256 key as the disk cache. A hit restores the module into an independent LWJGL buffer via `MemoryUtil.memCalloc` with native byte order, bypassing both disk and shaderc. Budget is 128 MB. When over budget, least-recently-used entries are evicted one by one; access time is updated on every memory hit and insertion. Oversized snapshots are not inserted and do not evict anything. Prefetch reads disk entries at startup and stops at half the budget. Requires `shaderCache`. |
| `purgeThrottle` | boolean | `true` | `true` / `false` | Throttles cache-directory housekeeping. The first directory walk in a session is unconditional; after that, walks happen no more than once per threshold `max(1 MB, cap/8)`, clamped to `cap/2`. With the default 64 MB cap this is 8192 KB. When `false`, the directory is walked on every cache write (pre-mode behavior). |
| `asyncStore` | boolean | `true` | `true` / `false` | Enables background cache writing. The compile thread only takes a heap snapshot of the SPIR-V; SHA-256 calculation, directory creation, and both atomic writes are performed by one background daemon thread. If the executor rejects the task, writing falls back to synchronous mode with byte-for-byte old behavior. |
| `inflightDedup` | Boolean wrapper | `true` | `true` / `false` | Single-flight for parallel cache misses. Parallel misses for the same shader key share the winner's compilation; waiting threads build their modules from the shared SPIR-V heap snapshot. A missing JSON key means default `true`; explicit `false` wins. `-Dpearloptimizer.inflightdedup=false` enables the pre-mode path. |
| `shaderOptimization` | boolean | `true` | `true` / `false` | Enables shaderc `PERFORMANCE` optimization level while keeping debug info. Debug info cannot be disabled because the game builds `vertexShaderInputsByName` through spvc reflection; without `OpName`, pipelines fail with `IllegalStateException: Duplicate key ""`. A runtime probe falls back to the vanilla profile if debug names do not survive. |
| `shaderOptimizationAllowElimination` | boolean | `false` | `true` / `false` | Opt-in flag that allows accepting an optimized stage if it removed only unused interface variables. The slot map is built from actual module reflection, so removal is structurally safe, but the flag remains opt-in until stand validation. |
| `shaderCacheMaxSizeMb` | integer | `64` | clamp `[8, 1024]` | Maximum size of the shader cache directory in MB. When exceeded, oldest entries are evicted by LRU. |
| `verboseLog` | boolean | `false` | `true` / `false` | Enables the detailed module report, HIT/MISS lines, and diagnostics. Warnings are printed regardless of this flag. |

---

## JVM system properties

Every JSON field has a matching `-Dpearloptimizer.*` system property. Properties are applied after the JSON is read and therefore always win over the file — handy for A/B testing or one-off diagnostics without editing the config.

| Property | Example | Action | JSON field |
|---|---|---|---|
| `pearloptimizer.buffers` | `=false` | Disable dynamic buffer presizing | `bumpInitialCapacity` |
| `pearloptimizer.capacity` | `=128` | Initial capacity in blocks (`0` = vanilla) | `initialCapacityBlocks` |
| `pearloptimizer.pipelinecache` | `=false` | Disable the shared `VkPipelineCache` | `sharedPipelineCache` |
| `pearloptimizer.pipecachedisk` | `=false` | Disable the persistent pipeline cache | `persistentPipelineCache` |
| `pearloptimizer.objcache` | `=false` | Disable the Vulkan object duplicate cache | `objectCache` |
| `pearloptimizer.presentmode` | `=mailbox` | `vanilla` or `mailbox` | `presentMode` |
| `pearloptimizer.shadercache` | `=false` | Disable the on-disk shader cache | `shaderCache` |
| `pearloptimizer.spirvmemory` | `=false` | Disable the in-memory SPIR-V cache | `spirvMemoryCache` |
| `pearloptimizer.purgethrottle` | `=false` | Walk the cache directory on every write (pre-mode) | `purgeThrottle` |
| `pearloptimizer.asyncstore` | `=false` | Use synchronous cache writing (pre-mode) | `asyncStore` |
| `pearloptimizer.inflightdedup` | `=false` | Disable single-flight compilation (pre-mode) | `inflightDedup` |
| `pearloptimizer.shadopt` | `=false` | Use the vanilla shaderc profile (`level 0` + debug info) | `shaderOptimization` |
| `pearloptimizer.shadoptelim` | `=true` | Allow elimination-only optimization (opt-in) | `shaderOptimizationAllowElimination` |
| `pearloptimizer.cachemaxmb` | `=256` | Shader cache size limit in MB | `shaderCacheMaxSizeMb` |
| `pearloptimizer.log` | `=true` | Enable the detailed module report and diagnostics | `verboseLog` |

**Boolean parsing rules:** for boolean properties, any value other than `false` (case-insensitive) means enabled. The single exception is `pearloptimizer.shadoptelim`, which is enabled only by an explicit `true`.

---
