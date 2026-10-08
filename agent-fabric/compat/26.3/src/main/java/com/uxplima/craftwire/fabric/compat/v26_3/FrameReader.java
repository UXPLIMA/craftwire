package com.uxplima.craftwire.fabric.compat.v26_3;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/** Minecraft 26.3: asynchronous GPU readback of the main render target for video frames (pooled buffers). */
final class FrameReader {
    private final ArrayDeque<GpuBuffer> free = new ArrayDeque<>();
    private long bufferSize;

    void read(RenderTarget target, Consumer<ByteBuffer> done) {
        GpuTexture texture = target.getColorTexture();
        if (texture == null) throw new IllegalStateException("the frame has no colour texture");
        long size = (long) target.width * target.height * texture.getFormat().blockSize();
        if (size != bufferSize) {
            release();
            bufferSize = size;
        }
        GpuBuffer buffer = free.poll();
        if (buffer == null) {
            buffer = RenderSystem.getDevice().createBuffer(() -> "Craftwire video frame", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
        }
        GpuBuffer b = buffer;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, b, 0, () -> {
            try (GpuBufferSlice.MappedView view = b.map(true, false)) {
                done.accept(view.data());
            } finally {
                if (b.size() == bufferSize) free.add(b);
                else b.close();
            }
        }, 0);
    }

    void release() {
        for (GpuBuffer b : free) b.close();
        free.clear();
        bufferSize = 0;
    }
}
