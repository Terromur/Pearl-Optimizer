package dev.zai.pearloptimizer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Raises the initial capacity (in blocks) of the dynamic GPU data storages.
 *
 * <p>Vanilla 26.3 constructs all five storages - Dynamic Transforms UBO,
 * Terrain UBO, Chunk Sections UBO, Chunk Sections Instanced and Chunk Sections
 * Command Buffer - with an initial capacity of 1-2 blocks. Every capacity
 * growth re-creates the GPU buffer, re-allocates the CPU mirror and queues the
 * old buffer for destruction, so the first seconds of a world load run through
 * a 1 -&gt; 2 -&gt; 4 -&gt; ... -&gt; 512 cascade (nine reallocations per storage).</p>
 *
 * <p>This helper only ever makes the initial value <b>bigger</b>, keeping it a
 * valid argument for the vanilla power-of-two rounding. Steady-state capacity
 * and all vanilla growth logic stay untouched: if a frame still needs more
 * blocks, the vanilla resize path runs exactly as before.</p>
 */
public final class CapacityBumps {
    private static final Set<String> LOGGED_FAMILIES = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private CapacityBumps() {
    }

    public static int apply(String family, int original) {
        try {
            PearlConfig config = PearlConfig.get();
            if (!config.bumpInitialCapacity) {
                return original;
            }
            int wanted = config.effectiveInitialCapacity();
            if (wanted <= original) {
                return original;
            }
            logBump(config, family, original, wanted);
            return wanted;
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                PearlOptimizer.LOGGER.warn(
                    "Capacity bump failed, keeping vanilla initial capacity", t);
            }
            return original;
        }
    }

    private static void logBump(PearlConfig config, String family, int original, int wanted) {
        if (!config.verboseLog || !LOGGED_FAMILIES.add(family)) {
            return;
        }
        try {
            PearlOptimizer.LOGGER.info(
                "Dynamic storages ({}): initial capacity {} -> {} blocks. Affects Dynamic Transforms UBO, "
                    + "Terrain UBO, Chunk Sections UBO, Chunk Sections Instanced and Chunk Sections Command "
                    + "Buffer; vanilla resize logic stays active as a safety net",
                family, original, wanted);
        } catch (Exception ignored) {
            // logging must never roll back the bump
        }
    }
}
