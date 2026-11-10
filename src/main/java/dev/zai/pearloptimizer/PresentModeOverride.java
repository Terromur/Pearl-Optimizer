package dev.zai.pearloptimizer;

import com.mojang.renderpearl.api.device.GpuSurface;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional present mode override for the swapchain.
 *
 * <p>Vulkan 26.3 builds the swapchain with the present mode chosen by the game
 * settings (FIFO when vsync is on, IMMEDIATE when off). With
 * {@code presentMode = "mailbox"} this helper substitutes
 * {@code VK_PRESENT_MODE_MAILBOX_KHR} whenever the game asked for FIFO and the
 * surface actually supports MAILBOX. MAILBOX keeps tearing-free vsync-style
 * presentation but replaces the queued frame instead of blocking the queue,
 * which lowers input latency; on a GPU-bound setup it also lets the GPU run at
 * full speed instead of throttling to the refresh rate, so expect higher FPS
 * and higher power draw.</p>
 *
 * <p>The override is applied only when the surface reports MAILBOX support
 * (the same set vanilla enumerates via {@code vkGetPhysicalDeviceSurfacePresentModesKHR}),
 * otherwise the vanilla value passes through untouched.</p>
 */
public final class PresentModeOverride {
    /** Exactly one log line per session, regardless of how many surfaces ask. */
    private static final AtomicBoolean REPORTED = new AtomicBoolean(false);

    private PresentModeOverride() {
    }

    public static int apply(GpuSurface.PresentMode mode, int vkValue, Set<GpuSurface.PresentMode> supported) {
        try {
            PearlConfig config = PearlConfig.get();
            if (!config.mailboxRequested()) {
                return vkValue;
            }
            if (mode != GpuSurface.PresentMode.FIFO) {
                return vkValue;
            }
            if (supported == null || !supported.contains(GpuSurface.PresentMode.MAILBOX)) {
                logOnce(config, "Present mode override: surface does not support MAILBOX, keeping the vanilla mode");
                return vkValue;
            }
            logOnce(config, "Present mode override: FIFO -> MAILBOX (supported by the surface). "
                + "Lower input latency, higher FPS and power draw on GPU-bound scenes");
            return org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
        } catch (Throwable t) {
            PearlOptimizer.LOGGER.warn("Present mode override failed, keeping the vanilla mode", t);
            return vkValue;
        }
    }

    /** One INFO line per session, gated by verboseLog and the REPORTED latch. Never throws. */
    private static void logOnce(PearlConfig config, String message) {
        if (!config.verboseLog || !REPORTED.compareAndSet(false, true)) {
            return;
        }
        try {
            PearlOptimizer.LOGGER.info(message);
        } catch (Exception ignored) {
            // logging must never break the override
        }
    }
}
