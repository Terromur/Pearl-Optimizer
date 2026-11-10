package dev.zai.pearloptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.zai.pearloptimizer.SharedPipelineCache;
import dev.zai.pearloptimizer.VulkanObjectCache;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.LongBuffer;

/**
 * Vanilla 26.3 compiles every graphics pipeline with {@code VK_NULL_HANDLE} as
 * the pipeline cache (both {@code vkCreateGraphicsPipelines} call sites in the
 * static {@code compile} method). We substitute one shared, lazily created
 * {@code VkPipelineCache} per device.
 *
 * <p>The same mixin also deduplicates the create-once objects that
 * vanilla rebuilds on every compile: {@code VkShaderModule} (keyed by the
 * SHA-256 of the SPIR-V), {@code VkDescriptorSetLayout} (keyed by the ordered
 * binding list) and {@code VkPipelineLayout} (keyed by the descriptor set
 * layout registry record + push constant ranges) go through the refcounted
 * {@link VulkanObjectCache}; a cached handle is written into the very
 * {@code LongBuffer} the vanilla call would have filled, so the rest of
 * {@code compile} is untouched. The three {@code vkDestroy*} call sites in
 * {@code destroy} hand their handles to the refcount registry and only fall
 * through to vanilla for objects the cache never owned. SPIR-V content,
 * reflection, binding order and pipeline creation are not modified.</p>
 *
 * <p>{@code compile} is static, therefore the handlers targeting it must be
 * static as well - a non-static handler targeting a static method makes Mixin
 * reject the whole class and crashes the game at startup. The {@code destroy}
 * handlers are instance methods for the same reason.</p>
 *
 * <p>The substitution contracts are explicit: the shared
 * pipeline cache replaces ONLY an incoming {@code VK_NULL_HANDLE} argument
 * (any other value passes through untouched) and a failed creation with the
 * substituted cache is replayed with the vanilla argument, so a cache problem
 * can never make pipeline creation worse than vanilla. The object-cache
 * acquires additionally refuse to cache when vanilla passes a non-null
 * allocator (cached objects are always created with the null allocator, so a
 * foreign allocator must stay on the vanilla path).</p>
 */
@Mixin(VulkanRenderPipeline.class)
public abstract class VulkanRenderPipelineMixin {

    @WrapOperation(
        method = "compile",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
        )
    )
    private static int pearloptimizer$sharedPipelineCache(
        VkDevice device,
        long pipelineCache,
        VkGraphicsPipelineCreateInfo.Buffer createInfos,
        VkAllocationCallbacks allocator,
        LongBuffer pipelines,
        Operation<Integer> operation
    ) {
        // substitute the shared cache only when vanilla actually
        // passed VK_NULL_HANDLE - any other incoming value passes through
        // untouched (when the module is disabled, forDevice returns 0 and the
        // call is byte-for-byte vanilla anyway). On a failed creation with the
        // substituted cache, replay the call with the vanilla argument: the
        // spec-defined failure behavior (all pPipelines entries written as
        // VK_NULL_HANDLE) makes the replay well-defined, and the mod can never
        // make pipeline creation worse than vanilla (fail-open).
        long shared = pipelineCache == VK12.VK_NULL_HANDLE
            ? SharedPipelineCache.forDevice(device)
            : pipelineCache;
        int rc = operation.call(device, shared, createInfos, allocator, pipelines);
        if (rc != VK12.VK_SUCCESS && shared != pipelineCache) {
            rc = operation.call(device, pipelineCache, createInfos, allocator, pipelines);
        }
        return rc;
    }

    @WrapOperation(
        method = "compile",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateShaderModule(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkShaderModuleCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
        )
    )
    private static int pearloptimizer$cachedShaderModule(
        VkDevice device,
        VkShaderModuleCreateInfo createInfo,
        VkAllocationCallbacks allocator,
        LongBuffer shaderModule,
        Operation<Integer> operation
    ) {
        // a non-null allocator means a foreign creation path;
        // cached objects are always created with the null allocator, so such
        // calls are never served from (or stored into) the cache.
        long cached = allocator == null
            ? VulkanObjectCache.acquireShaderModule(device, createInfo)
            : 0L;
        if (cached != 0L) {
            // Same write the native call would have performed: the handle at the
            // buffer's current position, result VK_SUCCESS. Vanilla continues with
            // the cached handle and never learns the difference.
            shaderModule.put(shaderModule.position(), cached);
            return VK12.VK_SUCCESS;
        }
        return operation.call(device, createInfo, allocator, shaderModule);
    }

    @WrapOperation(
        method = "compile",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateDescriptorSetLayout(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkDescriptorSetLayoutCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
        )
    )
    private static int pearloptimizer$cachedDescriptorSetLayout(
        VkDevice device,
        VkDescriptorSetLayoutCreateInfo createInfo,
        VkAllocationCallbacks allocator,
        LongBuffer setLayout,
        Operation<Integer> operation
    ) {
        // see pearloptimizer$cachedShaderModule - foreign allocator never cached.
        long cached = allocator == null
            ? VulkanObjectCache.acquireDescriptorSetLayout(device, createInfo)
            : 0L;
        if (cached != 0L) {
            setLayout.put(setLayout.position(), cached);
            return VK12.VK_SUCCESS;
        }
        return operation.call(device, createInfo, allocator, setLayout);
    }

    @WrapOperation(
        method = "compile",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreatePipelineLayout(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkPipelineLayoutCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"
        )
    )
    private static int pearloptimizer$cachedPipelineLayout(
        VkDevice device,
        VkPipelineLayoutCreateInfo createInfo,
        VkAllocationCallbacks allocator,
        LongBuffer pipelineLayout,
        Operation<Integer> operation
    ) {
        // see pearloptimizer$cachedShaderModule - foreign allocator never cached.
        long cached = allocator == null
            ? VulkanObjectCache.acquirePipelineLayout(device, createInfo)
            : 0L;
        if (cached != 0L) {
            pipelineLayout.put(pipelineLayout.position(), cached);
            return VK12.VK_SUCCESS;
        }
        return operation.call(device, createInfo, allocator, pipelineLayout);
    }

    @WrapOperation(
        method = "destroy",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkDestroyPipelineLayout(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"
        )
    )
    private void pearloptimizer$releasePipelineLayout(
        VkDevice device,
        long pipelineLayout,
        VkAllocationCallbacks allocator,
        Operation<Void> operation
    ) {
        if (!VulkanObjectCache.releasePipelineLayout(device, pipelineLayout)) {
            operation.call(device, pipelineLayout, allocator);
        }
    }

    @WrapOperation(
        method = "destroy",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkDestroyDescriptorSetLayout(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"
        )
    )
    private void pearloptimizer$releaseDescriptorSetLayout(
        VkDevice device,
        long setLayout,
        VkAllocationCallbacks allocator,
        Operation<Void> operation
    ) {
        if (!VulkanObjectCache.releaseDescriptorSetLayout(device, setLayout)) {
            operation.call(device, setLayout, allocator);
        }
    }

    @WrapOperation(
        method = "destroy",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkDestroyShaderModule(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"
        )
    )
    private void pearloptimizer$releaseShaderModule(
        VkDevice device,
        long shaderModule,
        VkAllocationCallbacks allocator,
        Operation<Void> operation
    ) {
        if (!VulkanObjectCache.releaseShaderModule(device, shaderModule)) {
            operation.call(device, shaderModule, allocator);
        }
    }
}
