# GUI Capture with a Transparent Background — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `screenshot` and `record` can capture the open GUI and/or its tooltip on a transparent background (PNG, ProRes 4444 `.mov`, VP9 `.webm`), without changing what the player sees.

**Architecture:** After the game has drawn a normal frame (the existing `FrameRenderedMixin` point), a version-specific
`GuiCanvas` extracts the open screen a second time into the GUI render state (no HUD, no menu background), keeps the
strata the layer asks for, and lets the game's own `GuiRenderer` draw them into an offscreen `TextureTarget` cleared to
transparent. The pixels are read back with the existing asynchronous `readPixels`, turned into straight alpha by a
pure-Java `AlphaFrame`, and written as PNG or piped to ffmpeg with an alpha codec.

**Tech Stack:** Java 25 (Fabric, Mixin, Mojang names), Minecraft 26.2 and 26.3 compat projects, ffmpeg (`prores_ks`,
`libvpx-vp9`), TypeScript hub (zod), vitest, Fabric client game tests.

**Spec:** `docs/superpowers/specs/2026-10-13-gui-capture-design.md`

## Global Constraints

- Layers: `"world"` (default, today's behaviour), `"gui"`, `"tooltip"`, `"gui+tooltip"`.
- `split: true` only with `"gui+tooltip"`; files `<name>.gui.<ext>` and `<name>.tooltip.<ext>`.
- `guiScale` 1-8, applied only for the capture and restored afterwards.
- Images: PNG with alpha; trimmed to content plus 8 px unless `trim: false`; `.jpg` refused for a transparent layer.
- Videos: `.mov` = ProRes 4444 (`prores_ks`, `yuva444p10le`), `.webm` = VP9 alpha (`libvpx-vp9`, `yuva420p`, `-b:v 0` with `crf`); `.mp4` refused for a transparent layer; full window size, no trim; sound `aac` in `.mov`, `opus` in `.webm`; camera motions refused with a transparent layer.
- The player's own frame never changes because of a capture.
- Errors: `NO_SCREEN`, `NO_TOOLTIP`, `INVALID_PARAMS`, `FFMPEG_FAILED` (message names the missing encoder).
- Tool-list budgets: whole list ≤ 28,000 chars, `--profile build` ≤ 10,500, any one tool ≤ 1,800.
- Version-specific code only in `agent-fabric/compat/26.2` and `agent-fabric/compat/26.3`.
- Local builds: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1"`; client game tests: `./gradlew :fabric-gametest-26.2:runClientGameTest` (and `26.3`).

## Review Focus

1. Hovering an enchanted item (glint) or a player head (3D item model) with a transparent layer: the item is drawn opaque, not punched-through or missing. Test in Task 3 (`enchantedItemIsOpaque`).
2. The screen closes during a `gui` recording: the recording keeps going with fully transparent frames and ends normally, no crash. Test in Task 5 (`screenClosingMidRecordingKeepsRecording`).
3. `guiScale` larger than the window allows (vanilla clamps it): the capture uses the clamped scale and the user's setting is restored exactly. Test in Task 4 (`guiScaleIsRestoredEvenWhenClamped`).
4. A recording of a transparent layer with an ffmpeg that lacks `prores_ks`/`libvpx-vp9`: `FFMPEG_FAILED` names the encoder before any frame is captured. Test in Task 5 (`missingEncoderIsNamed`, via `FfmpegArgs.requiredEncoder` + a fake encoder list).
5. The window is resized between two transparent screenshots: the canvas follows the new size (no stretched or cut image). Test in Task 4 (`canvasFollowsWindowSize`).

---

### Task 1: AlphaFrame — straight alpha, flip, trim, difference matte

**Files:**
- Create: `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/capture/AlphaFrame.java`
- Test: `agent-fabric/src/test/java/com/uxplima/craftwire/fabric/capture/AlphaFrameTest.java`

**Interfaces:**
- Produces:
  - `static BufferedImage AlphaFrame.fromPremultiplied(ByteBuffer rgba, int width, int height)` — rows bottom-up, premultiplied colour → TYPE_INT_ARGB, top-down, straight alpha.
  - `static BufferedImage AlphaFrame.matte(ByteBuffer overBlack, ByteBuffer overWhite, int width, int height)` — difference matte (reference for tests).
  - `static BufferedImage AlphaFrame.trim(BufferedImage img, int pad)` — crop to the bounding box of alpha > 0 plus `pad` (clamped to the image); an image with no visible pixel is returned unchanged.
  - `static int AlphaFrame.maxDifference(BufferedImage a, BufferedImage b, int minAlpha)` — largest channel difference over pixels where either alpha ≥ minAlpha (tests).

- [ ] **Step 1: Write the failing tests**

```java
package com.uxplima.craftwire.fabric.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class AlphaFrameTest {
    /** 2x2 RGBA, rows bottom-up: pixels given top-down as {r,g,b,a}. */
    static ByteBuffer frame(int[][] topDown, int w, int h) {
        ByteBuffer b = ByteBuffer.allocate(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int[] p = topDown[(h - 1 - y) * w + x];
                b.put((byte) p[0]).put((byte) p[1]).put((byte) p[2]).put((byte) p[3]);
            }
        }
        return b.flip();
    }

    @Test
    void premultipliedColourBecomesStraightAndRowsFlip() {
        // top-left: half-transparent red stored premultiplied (128,0,0,128); bottom-right opaque blue; others empty
        ByteBuffer f = frame(new int[][] {{128, 0, 0, 128}, {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 255, 255}}, 2, 2);
        BufferedImage img = AlphaFrame.fromPremultiplied(f, 2, 2);
        assertEquals(0x80FF0000, img.getRGB(0, 0));
        assertEquals(0x00000000, img.getRGB(1, 0));
        assertEquals(0xFF0000FF, img.getRGB(1, 1));
    }

    @Test
    void matteRecoversAlphaFromBlackAndWhite() {
        // 50% white over black = 128 grey; over white = 255
        ByteBuffer black = frame(new int[][] {{128, 128, 128, 255}, {0, 0, 0, 255}, {0, 0, 0, 255}, {0, 0, 0, 255}}, 2, 2);
        ByteBuffer white = frame(new int[][] {{255, 255, 255, 255}, {255, 255, 255, 255}, {255, 255, 255, 255}, {0, 0, 0, 255}}, 2, 2);
        BufferedImage m = AlphaFrame.matte(black, white, 2, 2);
        int top = m.getRGB(0, 0);
        assertTrue(Math.abs((top >>> 24) - 128) <= 1, Integer.toHexString(top));
        assertTrue(Math.abs((top >> 16 & 255) - 255) <= 2, Integer.toHexString(top));
        assertEquals(0, m.getRGB(1, 0) >>> 24);                 // black over black, white over white: nothing there
        assertEquals(0xFF000000, m.getRGB(1, 1));                // opaque black in both
    }

    @Test
    void trimKeepsContentPlusPadding() {
        BufferedImage img = new BufferedImage(100, 50, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(40, 20, 0xFFFFFFFF);
        img.setRGB(59, 29, 0x10FFFFFF);
        BufferedImage t = AlphaFrame.trim(img, 8);
        assertEquals(20 + 16, t.getWidth());
        assertEquals(10 + 16, t.getHeight());
        assertEquals(0xFFFFFFFF, t.getRGB(8, 8));
        BufferedImage edge = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        edge.setRGB(0, 0, 0xFFFFFFFF);
        assertEquals(9, AlphaFrame.trim(edge, 8).getWidth());   // padding is clamped to the image
        BufferedImage empty = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        assertSame(empty, AlphaFrame.trim(empty, 8));
    }

    @Test
    void maxDifferenceIgnoresInvisiblePixels() {
        BufferedImage a = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        BufferedImage b = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        a.setRGB(0, 0, 0x01FF0000);   // nearly invisible: colour does not matter
        b.setRGB(0, 0, 0x0100FF00);
        a.setRGB(1, 0, 0xFF808080);
        b.setRGB(1, 0, 0xFF848080);
        assertEquals(4, AlphaFrame.maxDifference(a, b, 16));
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-fabric:test --tests '*AlphaFrameTest'`
Expected: FAIL — compilation error, `AlphaFrame` does not exist.

- [ ] **Step 3: Implement**

```java
package com.uxplima.craftwire.fabric.capture;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/** Pixels read back from the GPU (RGBA, rows bottom-up) as images with straight alpha. */
public final class AlphaFrame {
    private AlphaFrame() {}

    /** The GUI blends colour with SRC_ALPHA and alpha with ONE, so a canvas cleared to 0 holds premultiplied colour. */
    public static BufferedImage fromPremultiplied(ByteBuffer rgba, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            int base = (height - 1 - y) * width * 4;
            for (int x = 0; x < width; x++) {
                int i = base + x * 4;
                int a = rgba.get(i + 3) & 255;
                if (a == 0) {
                    row[x] = 0;
                    continue;
                }
                int r = Math.min(255, ((rgba.get(i) & 255) * 255 + a / 2) / a);
                int g = Math.min(255, ((rgba.get(i + 1) & 255) * 255 + a / 2) / a);
                int b = Math.min(255, ((rgba.get(i + 2) & 255) * 255 + a / 2) / a);
                row[x] = a << 24 | r << 16 | g << 8 | b;
            }
            img.setRGB(0, y, width, 1, row, 0, width);
        }
        return img;
    }

    /** Difference matte: the same layer over opaque black and opaque white. alpha = 1 - (white - black). */
    public static BufferedImage matte(ByteBuffer overBlack, ByteBuffer overWhite, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            int base = (height - 1 - y) * width * 4;
            for (int x = 0; x < width; x++) {
                int i = base + x * 4;
                int diff = 0;
                for (int c = 0; c < 3; c++) diff += (overWhite.get(i + c) & 255) - (overBlack.get(i + c) & 255);
                int a = Math.max(0, Math.min(255, 255 - Math.round(diff / 3f)));
                if (a == 0) {
                    row[x] = 0;
                    continue;
                }
                int r = Math.min(255, ((overBlack.get(i) & 255) * 255 + a / 2) / a);
                int g = Math.min(255, ((overBlack.get(i + 1) & 255) * 255 + a / 2) / a);
                int b = Math.min(255, ((overBlack.get(i + 2) & 255) * 255 + a / 2) / a);
                row[x] = a << 24 | r << 16 | g << 8 | b;
            }
            img.setRGB(0, y, width, 1, row, 0, width);
        }
        return img;
    }

    /** Crops to the visible pixels plus {@code pad} on each side (clamped); an empty image is returned as is. */
    public static BufferedImage trim(BufferedImage img, int pad) {
        int minX = img.getWidth(), minY = img.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (img.getRGB(x, y) >>> 24 == 0) continue;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        if (maxX < 0) return img;
        int x0 = Math.max(0, minX - pad), y0 = Math.max(0, minY - pad);
        int x1 = Math.min(img.getWidth() - 1, maxX + pad), y1 = Math.min(img.getHeight() - 1, maxY + pad);
        BufferedImage out = new BufferedImage(x1 - x0 + 1, y1 - y0 + 1, BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(img.getSubimage(x0, y0, out.getWidth(), out.getHeight()), 0, 0, null);
        return out;
    }

    /** Largest channel difference (a, r, g, b) over pixels where either image has alpha ≥ {@code minAlpha}. */
    public static int maxDifference(BufferedImage a, BufferedImage b, int minAlpha) {
        int max = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                if ((p >>> 24) < minAlpha && (q >>> 24) < minAlpha) continue;
                for (int s = 0; s < 32; s += 8) max = Math.max(max, Math.abs((p >>> s & 255) - (q >>> s & 255)));
            }
        }
        return max;
    }
}
```

`drawImage` of an ARGB sub-image onto a fresh ARGB image uses SRC_OVER onto transparent, which copies the pixels unchanged.

- [ ] **Step 4: Run them to see them pass**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-fabric:test --tests '*AlphaFrameTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add agent-fabric/src/main/java/com/uxplima/craftwire/fabric/capture/AlphaFrame.java agent-fabric/src/test/java/com/uxplima/craftwire/fabric/capture/AlphaFrameTest.java
git commit -m "feat(fabric): straight-alpha frames, trim and difference matte for GUI capture"
```

---

### Task 2: The GUI canvas (spike + compat for 26.2 and 26.3)

This task settles the approach (spec: "the first implementation task is a spike"). The canvas result must match the
difference matte within 6 per channel on pixels with alpha ≥ 16. If it does not, switch `GuiCanvas` to draw over
black and over white and return `AlphaFrame.matte` instead (the interface below stays the same), and ledger the switch.

**Files:**
- Create: `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/capture/GuiLayer.java`
- Modify: `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/compat/ClientCompat.java` (two methods)
- Create: `agent-fabric/compat/26.2/src/main/java/com/uxplima/craftwire/fabric/compat/v26_2/GuiCanvas.java`
- Create: `agent-fabric/compat/26.2/src/main/java/com/uxplima/craftwire/fabric/compat/v26_2/mixin/GuiCanvasMixins.java` (three small mixins in one file are not allowed by Mixin — create `ScreenBackgroundMixin.java`, `GuiRendererTargetMixin.java`, `GuiRendererAccessor.java`, `GuiRenderStateAccessor.java`, `GuiGraphicsExtractorAccessor.java`)
- Modify: `agent-fabric/compat/26.2/src/main/java/com/uxplima/craftwire/fabric/compat/v26_2/ClientCompatImpl.java`
- Modify: the 26.2 compat mixin config (`agent-fabric/compat/26.2/src/main/resources/craftwire-compat-26_2.mixins.json` — check the exact name with `ls agent-fabric/compat/26.2/src/main/resources`)
- The same five mixins, `GuiCanvas` and `ClientCompatImpl` change under `compat/26.3` (package `v26_3`; `TextureTarget(String, int, int, GpuFormat, GpuFormat)` and `com.mojang.renderpearl.api.*` types there)
- Test: `agent-fabric/src/gametest/java/com/uxplima/craftwire/fabric/gametest/GuiLayerChecks.java`, `AgentGameTests.java` (call it after `CaptureChecks`)

**Interfaces:**
- Consumes: `AlphaFrame.fromPremultiplied`, `AlphaFrame.matte`, `AlphaFrame.maxDifference` (Task 1); `ClientCompat.readPixels(RenderTarget, Consumer<ByteBuffer>)` (existing).
- Produces:
  - `enum GuiLayer { GUI, TOOLTIP, GUI_TOOLTIP; static GuiLayer parse(String) /* "gui","tooltip","gui+tooltip"; null for "world" */ }`
  - `record ClientCompat.Drawn(boolean screen, boolean tooltip)` (nested in `ClientCompat`)
  - `RenderTarget ClientCompat.guiCanvas(int width, int height)` — a reusable offscreen target with depth, recreated when the size changes. Render thread.
  - `ClientCompat.Drawn ClientCompat.drawGui(GuiLayer layer, RenderTarget canvas, int clearArgb)` — clears `canvas` to `clearArgb` (0 for transparent), draws the open screen's layer into it, returns what was there. Render thread. Never touches the main render target.

- [ ] **Step 1: Write the failing game test**

```java
package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.uxplima.craftwire.fabric.capture.AlphaFrame;
import com.uxplima.craftwire.fabric.capture.GuiLayer;
import com.uxplima.craftwire.fabric.compat.ClientCompat;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** The transparent GUI canvas on the real renderer (Task 2 spike; later tasks add more). */
final class GuiLayerChecks {
    private GuiLayerChecks() {}

    record Shot(BufferedImage image, ClientCompat.Drawn drawn) {}

    /** Draws the layer on the render thread and reads it back (two frames later). */
    static Shot draw(ClientGameTestContext ctx, GuiLayer layer, int clearArgb) throws Exception {
        CompletableFuture<ByteBuffer> pixels = new CompletableFuture<>();
        int[] size = new int[2];
        ClientCompat.Drawn drawn = ctx.computeOnClient(mc -> {
            RenderTarget main = mc.gameRenderer.mainRenderTarget();
            RenderTarget canvas = ClientCompat.get().guiCanvas(main.width, main.height);
            ClientCompat.Drawn d = ClientCompat.get().drawGui(layer, canvas, clearArgb);
            size[0] = canvas.width;
            size[1] = canvas.height;
            ClientCompat.get().readPixels(canvas, b -> {
                ByteBuffer copy = ByteBuffer.allocate(b.remaining());
                copy.put(b).flip();
                pixels.complete(copy);
            });
            return d;
        });
        ctx.waitTicks(3);
        ByteBuffer b = pixels.get(10, TimeUnit.SECONDS);
        return new Shot(clearArgb == 0 ? AlphaFrame.fromPremultiplied(b, size[0], size[1]) : rawImage(b, size[0], size[1]), drawn);
    }

    /** An opaque background frame kept as raw RGBA (for the matte). */
    static BufferedImage rawImage(ByteBuffer b, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = ((h - 1 - y) * w + x) * 4;
            img.setRGB(x, y, (b.get(i + 3) & 255) << 24 | (b.get(i) & 255) << 16 | (b.get(i + 1) & 255) << 8 | (b.get(i + 2) & 255));
        }
        return img;
    }

    static ByteBuffer rawBuffer(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        ByteBuffer b = ByteBuffer.allocate(w * h * 4);
        for (int y = h - 1; y >= 0; y--) for (int x = 0; x < w; x++) {
            int p = img.getRGB(x, y);
            b.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
        }
        return b.flip();
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) throws Exception {
        // A red block right in front of the player: it must never appear in a GUI layer.
        sp.getServer().runCommand("setblock ~ ~1 ~2 minecraft:red_concrete");
        ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
        ctx.waitTicks(3);

        Shot gui = draw(ctx, GuiLayer.GUI, 0);
        check(gui.drawn().screen(), "a screen was drawn");
        BufferedImage img = gui.image();
        check(img.getRGB(2, 2) >>> 24 == 0, "corner must be transparent: " + Integer.toHexString(img.getRGB(2, 2)));
        int cx = img.getWidth() / 2, cy = img.getHeight() / 2;
        check(img.getRGB(cx, cy) >>> 24 == 255, "the inventory panel centre must be opaque: " + Integer.toHexString(img.getRGB(cx, cy)));
        for (int y = 0; y < img.getHeight(); y += 4) for (int x = 0; x < img.getWidth(); x += 4) {
            int p = img.getRGB(x, y);
            boolean red = (p >>> 24) > 200 && (p >> 16 & 255) > 150 && (p >> 8 & 255) < 60 && (p & 255) < 60;
            check(!red, "the world (red concrete) must not show at " + x + "," + y);
        }

        // The canvas must agree with the difference matte (the approach check of the spike).
        BufferedImage black = draw(ctx, GuiLayer.GUI, 0xFF000000).image();
        BufferedImage white = draw(ctx, GuiLayer.GUI, 0xFFFFFFFF).image();
        BufferedImage matte = AlphaFrame.matte(rawBuffer(black), rawBuffer(white), img.getWidth(), img.getHeight());
        int diff = AlphaFrame.maxDifference(img, matte, 16);
        check(diff <= 6, "canvas vs matte differ by " + diff);

        // The player's own frame still shows the world behind the screen.
        ctx.waitTicks(2);
        CompletableFuture<ByteBuffer> main = new CompletableFuture<>();
        int[] ms = ctx.computeOnClient(mc -> {
            RenderTarget t = mc.gameRenderer.mainRenderTarget();
            ClientCompat.get().readPixels(t, b -> {
                ByteBuffer c = ByteBuffer.allocate(b.remaining());
                c.put(b).flip();
                main.complete(c);
            });
            return new int[] {t.width, t.height};
        });
        ctx.waitTicks(3);
        BufferedImage frame = rawImage(main.get(10, TimeUnit.SECONDS), ms[0], ms[1]);
        check(frame.getRGB(2, 2) >>> 24 == 255 && (frame.getRGB(2, 2) & 0xFFFFFF) != 0, "the player's frame is untouched");

        Calls.call(ctx, "gui.action", "{\"action\":\"close\"}");
        sp.getServer().runCommand("setblock ~ ~1 ~2 minecraft:air");
    }
}
```

Add `GuiLayerChecks.run(ctx, sp);` after `CaptureChecks.run(ctx, sp);` in `AgentGameTests` (inside the same try; widen the catch to `Exception` → `AssertionError`).

- [ ] **Step 2: Run it to see it fail**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :fabric-gametest-26.2:runClientGameTest`
Expected: FAIL — compilation errors (`GuiLayer`, `ClientCompat.guiCanvas`, `drawGui` missing).

- [ ] **Step 3: Core types**

```java
package com.uxplima.craftwire.fabric.capture;

/** What a transparent capture shows. "world" (today's capture) is not a GuiLayer. */
public enum GuiLayer {
    GUI, TOOLTIP, GUI_TOOLTIP;

    /** "gui", "tooltip", "gui+tooltip"; null for "world" or when absent. */
    public static GuiLayer parse(String s) {
        if (s == null || s.equals("world")) return null;
        return switch (s) {
            case "gui" -> GUI;
            case "tooltip" -> TOOLTIP;
            case "gui+tooltip" -> GUI_TOOLTIP;
            default -> throw new IllegalArgumentException("layer must be world, gui, tooltip or gui+tooltip");
        };
    }

    public boolean screen() { return this != TOOLTIP; }

    public boolean tooltip() { return this != GUI; }
}
```

In `ClientCompat` add:

```java
    /** What {@link #drawGui} found: an open screen, and a tooltip shown on it. */
    record Drawn(boolean screen, boolean tooltip) {}

    /** A reusable offscreen target (colour + depth) of this size; recreated when the size changes. Render thread. */
    RenderTarget guiCanvas(int width, int height);

    /**
     * Clears {@code canvas} to {@code clearArgb} and draws the open screen's {@code layer} into it: no world, no HUD,
     * no menu background or blur. The main render target is not touched. Render thread.
     */
    Drawn drawGui(com.uxplima.craftwire.fabric.capture.GuiLayer layer, RenderTarget canvas, int clearArgb);
```

- [ ] **Step 4: 26.2 mixins**

`GuiRendererAccessor`:

```java
package com.uxplima.craftwire.fabric.compat.v26_2.mixin;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiRenderer.class)
public interface GuiRendererAccessor {
    @Accessor("renderState")
    GuiRenderState craftwire$renderState();
}
```

`GameRendererGuiAccessor` (add to the same list; `GameRenderer.guiRenderer` is private):

```java
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public interface GameRendererGuiAccessor {
    @Accessor("guiRenderer")
    net.minecraft.client.gui.render.GuiRenderer craftwire$guiRenderer();
}
```

`GuiRenderStateAccessor`:

```java
@Mixin(net.minecraft.client.renderer.state.gui.GuiRenderState.class)
public interface GuiRenderStateAccessor {
    @Accessor("strata")
    java.util.List<Object> craftwire$strata();   // GuiRenderState$Node is package-private: handled as Object
}
```

`GuiGraphicsExtractorAccessor`:

```java
@Mixin(net.minecraft.client.gui.GuiGraphicsExtractor.class)
public interface GuiGraphicsExtractorAccessor {
    @Accessor("deferredTooltip")
    Runnable craftwire$deferredTooltip();
}
```

`ScreenBackgroundMixin` — the canvas pass skips the menu background (darkening, blur); the player's frame keeps it:

```java
package com.uxplima.craftwire.fabric.compat.v26_2.mixin;

import com.uxplima.craftwire.fabric.compat.v26_2.GuiCanvas;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenBackgroundMixin {
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void craftwire$noBackgroundOnCanvas(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
        if (GuiCanvas.extracting()) ci.cancel();
    }
}
```

`GuiRendererTargetMixin` — while the canvas draws, `GuiRenderer.draw` targets the canvas instead of the main target:

```java
package com.uxplima.craftwire.fabric.compat.v26_2.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.uxplima.craftwire.fabric.compat.v26_2.GuiCanvas;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererTargetMixin {
    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget craftwire$canvasTarget(GameRenderer renderer) {
        RenderTarget canvas = GuiCanvas.drawingInto();
        return canvas != null ? canvas : renderer.mainRenderTarget();
    }
}
```

Register all six in the 26.2 compat mixin config under `"client"`.

- [ ] **Step 5: 26.2 GuiCanvas**

```java
package com.uxplima.craftwire.fabric.compat.v26_2;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.uxplima.craftwire.fabric.capture.GuiLayer;
import com.uxplima.craftwire.fabric.compat.ClientCompat;
import com.uxplima.craftwire.fabric.compat.v26_2.mixin.GameRendererGuiAccessor;
import com.uxplima.craftwire.fabric.compat.v26_2.mixin.GuiGraphicsExtractorAccessor;
import com.uxplima.craftwire.fabric.compat.v26_2.mixin.GuiRenderStateAccessor;
import com.uxplima.craftwire.fabric.compat.v26_2.mixin.GuiRendererAccessor;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Vector4f;

/**
 * Minecraft 26.2: draws the open screen a second time, after the frame, into an offscreen target. The screen is
 * extracted again into the GUI render state without its background; strata are trimmed to the layer (the deferred
 * tooltip is the last stratum); the game's GuiRenderer draws them with the canvas as its target.
 */
public final class GuiCanvas {
    private static boolean extracting;
    private static RenderTarget drawingInto;
    private RenderTarget canvas;

    public static boolean extracting() { return extracting; }

    public static RenderTarget drawingInto() { return drawingInto; }

    RenderTarget canvas(int width, int height) {
        if (canvas != null && (canvas.width != width || canvas.height != height)) {
            canvas.destroyBuffers();
            canvas = null;
        }
        if (canvas == null) canvas = new TextureTarget("Craftwire GUI canvas", width, height, true, GpuFormat.RGBA8_UNORM);
        return canvas;
    }

    ClientCompat.Drawn draw(GuiLayer layer, RenderTarget target, int clearArgb) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        Vector4f clear = new Vector4f((clearArgb >> 16 & 255) / 255f, (clearArgb >> 8 & 255) / 255f, (clearArgb & 255) / 255f, (clearArgb >>> 24) / 255f);
        encoder.clearColorAndDepthTextures(target.getColorTexture(), clear, target.getDepthTexture(), 1.0);
        if (screen == null) return new ClientCompat.Drawn(false, false);

        GuiRenderer renderer = ((GameRendererGuiAccessor) mc.gameRenderer).craftwire$guiRenderer();
        GuiRenderState state = ((GuiRendererAccessor) renderer).craftwire$renderState();
        var window = mc.getWindow();
        int mx = (int) mc.mouseHandler.getScaledXPos(window), my = (int) mc.mouseHandler.getScaledYPos(window);
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        state.reset();
        GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, mx, my);
        boolean tooltip;
        extracting = true;
        try {
            screen.extractRenderStateWithTooltipAndSubtitles(g, mx, my, partial);
            tooltip = ((GuiGraphicsExtractorAccessor) g).craftwire$deferredTooltip() != null;
            g.extractDeferredElements(mx, my, partial);
        } finally {
            extracting = false;
        }
        List<Object> strata = ((GuiRenderStateAccessor) state).craftwire$strata();
        if (tooltip && layer == GuiLayer.GUI) strata.removeLast();
        if (layer == GuiLayer.TOOLTIP) {
            Object last = tooltip ? strata.getLast() : null;
            strata.clear();
            if (last != null) strata.add(last);
        }
        drawingInto = target;
        try {
            renderer.render();
        } finally {
            drawingInto = null;
            renderer.endFrame();
        }
        return new ClientCompat.Drawn(true, tooltip);
    }
}
```

In `ClientCompatImpl` (26.2):

```java
    private final GuiCanvas gui = new GuiCanvas();

    @Override
    public RenderTarget guiCanvas(int width, int height) {
        return gui.canvas(width, height);
    }

    @Override
    public Drawn drawGui(com.uxplima.craftwire.fabric.capture.GuiLayer layer, RenderTarget canvas, int clearArgb) {
        return gui.draw(layer, canvas, clearArgb);
    }
```

The exact names `getScaledXPos`, `getDeltaTracker`, `destroyBuffers`, `GpuFormat.RGBA8_UNORM` and whether
`extractRenderStateWithTooltipAndSubtitles` already calls `extractDeferredElements` are checked with
`javap -cp <loom 26.2 jar> -p <class>` while writing; where the plan's name differs, use the game's and ledger it.
If `extractRenderStateWithTooltipAndSubtitles` already runs the deferred tooltip, read `deferredTooltip` through an
`@Inject` at the `HEAD` of `extractDeferredElements` that sets a static flag instead, and drop the explicit call.

- [ ] **Step 6: Run the game test on 26.2**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :fabric-gametest-26.2:runClientGameTest`
Expected: PASS, including `canvas vs matte` ≤ 6. If the matte check fails, apply the fallback described at the top of
this task, re-run, and ledger the numbers. Save the three images (`img`, `black`, `white`) to
`build/run/craftwire-camera-shots/` while developing and look at them.

- [ ] **Step 7: 26.3**

Copy the six mixins and `GuiCanvas` into `compat/26.3` (package `v26_3`). In `GuiCanvas`: imports
`com.mojang.renderpearl.api.GpuFormat` (check with javap which package holds `GpuFormat`, `RenderSystem` and the
command encoder in 26.3) and `new TextureTarget("Craftwire GUI canvas", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT)`
(the depth format name from javap). Register the mixins in the 26.3 config and add the two methods to its `ClientCompatImpl`.

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :fabric-gametest-26.3:runClientGameTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add agent-fabric
git commit -m "feat(fabric): transparent GUI canvas (26.2, 26.3)"
```

---

### Task 3: Tooltip layer and item rendering on the canvas

**Files:**
- Modify: `agent-fabric/src/gametest/java/com/uxplima/craftwire/fabric/gametest/GuiLayerChecks.java`
- Modify (only if a check fails): the two `GuiCanvas` classes

**Interfaces:**
- Consumes: `ClientCompat.drawGui`, `GuiLayerChecks.draw` (Task 2).

- [ ] **Step 1: Write the failing checks**

Add to `GuiLayerChecks.run`, before the screen is closed:

```java
        // An enchanted book in the first hotbar slot, hovered: a tooltip with a translucent background.
        sp.getServer().runCommand("item replace entity @p hotbar.0 with minecraft:enchanted_book[minecraft:stored_enchantments={\"minecraft:sharpness\":5},minecraft:lore=['\"Craftwire lore line\"']]");
        ctx.waitTicks(3);
        Calls.call(ctx, "gui.action", "{\"action\":\"hover\",\"slot\":36}");
        ctx.waitTicks(3);
        enchantedItemIsOpaque(ctx);
        tooltipIsTranslucent(ctx);
```

and the two methods:

```java
    /** Review Focus #1: an item with glint is drawn opaque on the canvas. */
    static void enchantedItemIsOpaque(ClientGameTestContext ctx) throws Exception {
        int[] slot = ctx.computeOnClient(mc -> {
            var s = (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) mc.screen;
            var sl = s.getMenu().slots.get(36);
            int scale = mc.getWindow().getGuiScale();
            return new int[] {(((AbstractContainerScreenAccessor) s).craftwire$leftPos() + sl.x + 8) * scale,
                    (((AbstractContainerScreenAccessor) s).craftwire$topPos() + sl.y + 8) * scale};
        });
        BufferedImage img = draw(ctx, GuiLayer.GUI, 0).image();
        check(img.getRGB(slot[0], slot[1]) >>> 24 == 255, "the book's centre must be opaque: " + Integer.toHexString(img.getRGB(slot[0], slot[1])));
    }

    static void tooltipIsTranslucent(ClientGameTestContext ctx) throws Exception {
        Shot t = draw(ctx, GuiLayer.TOOLTIP, 0);
        check(t.drawn().tooltip(), "a tooltip was shown");
        BufferedImage img = t.image();
        int translucent = 0, opaque = 0, visible = 0;
        for (int y = 0; y < img.getHeight(); y += 2) for (int x = 0; x < img.getWidth(); x += 2) {
            int a = img.getRGB(x, y) >>> 24;
            if (a > 0) visible++;
            if (a > 0 && a < 255) translucent++;
            if (a == 255) opaque++;
        }
        check(translucent > 50, "tooltip background must be translucent (" + translucent + " px)");
        check(opaque > 10, "tooltip text must be opaque (" + opaque + " px)");
        // Only the tooltip: far fewer visible pixels than the GUI layer.
        Shot gui = draw(ctx, GuiLayer.GUI, 0);
        int guiVisible = 0;
        for (int y = 0; y < gui.image().getHeight(); y += 2) for (int x = 0; x < gui.image().getWidth(); x += 2) if (gui.image().getRGB(x, y) >>> 24 > 0) guiVisible++;
        check(visible < guiVisible, "tooltip layer must not contain the inventory (" + visible + " vs " + guiVisible + ")");
        Shot both = draw(ctx, GuiLayer.GUI_TOOLTIP, 0);
        check(both.drawn().tooltip() && both.drawn().screen(), "gui+tooltip draws both");
    }
```

`AbstractContainerScreenAccessor` lives in `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/mixin/` if it
already exists (`grep -rn "leftPos" agent-fabric/src/main/java/com/uxplima/craftwire/fabric/mixin`); otherwise create
it there with `@Accessor("leftPos") int craftwire$leftPos();` and `@Accessor("topPos") int craftwire$topPos();` and
register it in `craftwire.mixins.json`. Check that `gui.action hover` takes `slot` (it does in `GuiActionHandler`; use
its exact argument name).

- [ ] **Step 2: Run on 26.2**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :fabric-gametest-26.2:runClientGameTest`
Expected: PASS if Task 2's canvas already handles items and tooltips; if a check fails, the failure message names
the pixel or count. Fix in `GuiCanvas` (for example: an item atlas blit that needs the canvas's depth cleared to 1.0,
or a glint pipeline with additive blending — fall back to the matte for the item layer only if no other fix works,
and ledger it), then re-run until PASS.

- [ ] **Step 3: Run on 26.3**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :fabric-gametest-26.3:runClientGameTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add agent-fabric
git commit -m "test(fabric): tooltip layer and enchanted items on the transparent canvas"
```

---

### Task 4: `screenshot` with `layer`, `split`, `trim`, `guiScale`

**Files:**
- Create: `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/capture/GuiCapture.java`
- Modify: `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/handlers/ScreenshotHandler.java`
- Test: `agent-fabric/src/gametest/java/com/uxplima/craftwire/fabric/gametest/GuiLayerChecks.java` (screenshot section)
- Test: `agent-fabric/src/test/java/com/uxplima/craftwire/fabric/capture/GuiCaptureTest.java` (argument checks)

**Interfaces:**
- Consumes: `GuiLayer`, `AlphaFrame`, `ClientCompat.guiCanvas/drawGui/readPixels`.
- Produces:
  - `record GuiCapture.Options(GuiLayer layer, boolean split, boolean trim, Integer guiScale)` with `static Options parse(JsonObject p, String savePath)` — throws `AgentError INVALID_PARAMS` for `.jpg`/`.jpeg` with a layer, `split` without `GUI_TOOLTIP`, `guiScale` outside 1-8; returns `null` when `layer` is absent or `"world"`.
  - `static Path GuiCapture.splitPath(Path savePath, String part)` — `shot.png` → `shot.gui.png` / `shot.tooltip.png`.
  - `static CompletableFuture<List<BufferedImage>> GuiCapture.grab(ClientScheduler s, Options o)` — one image per part (`[gui, tooltip]` when split, else one); fails with `NO_SCREEN` / `NO_TOOLTIP`.
  - `static void GuiCapture.withGuiScale(Minecraft mc, Integer scale, Runnable body)`-style helpers are private; `guiScale` is applied before the capture and restored in the same `handle(...)` as the HUD.

- [ ] **Step 1: Write the failing unit test (argument rules)**

```java
package com.uxplima.craftwire.fabric.capture;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GuiCaptureTest {
    static GuiCapture.Options parse(String json, String save) {
        return GuiCapture.Options.parse(JsonParser.parseString(json).getAsJsonObject(), save);
    }

    @Test
    void worldOrNoLayerMeansAnOrdinaryScreenshot() {
        assertNull(parse("{}", null));
        assertNull(parse("{\"layer\":\"world\"}", "a.jpg"));
    }

    @Test
    void transparentLayersRefuseJpegAndBadCombinations() {
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> parse("{\"layer\":\"gui\"}", "a.jpg")).code());
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> parse("{\"layer\":\"gui\",\"split\":true}", "a.png")).code());
        assertEquals("INVALID_PARAMS", assertThrows(AgentError.class, () -> parse("{\"layer\":\"gui\",\"guiScale\":9}", null)).code());
        GuiCapture.Options o = parse("{\"layer\":\"gui+tooltip\",\"split\":true,\"guiScale\":4}", "a.png");
        assertEquals(GuiLayer.GUI_TOOLTIP, o.layer());
        assertTrue(o.split() && o.trim());
        assertEquals(4, o.guiScale());
    }

    @Test
    void splitFilesAreNamedAfterTheirPart() {
        assertEquals(Path.of("d", "shot.gui.png"), GuiCapture.splitPath(Path.of("d", "shot.png"), "gui"));
        assertEquals(Path.of("d", "clip.tooltip.mov"), GuiCapture.splitPath(Path.of("d", "clip.mov"), "tooltip"));
    }
}
```

`Params.invalid` throws `AgentError("INVALID_PARAMS", …)`, the agent's code for bad arguments (the hub uses the same code).

- [ ] **Step 2: Run to see it fail**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-fabric:test --tests '*GuiCaptureTest'`
Expected: FAIL — `GuiCapture` missing.

- [ ] **Step 3: Implement GuiCapture**

```java
package com.uxplima.craftwire.fabric.capture;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.ClientScheduler;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.compat.ClientCompat;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/** Screenshots of the open GUI and/or tooltip on a transparent background. */
public final class GuiCapture {
    private GuiCapture() {}

    public record Options(GuiLayer layer, boolean split, boolean trim, Integer guiScale) {
        public static Options parse(JsonObject p, String savePath) {
            GuiLayer layer;
            try {
                layer = GuiLayer.parse(Params.optString(p, "layer").orElse(null));
            } catch (IllegalArgumentException e) {
                throw Params.invalid(e.getMessage());
            }
            if (layer == null) return null;
            String name = savePath == null ? "" : savePath.toLowerCase(Locale.ROOT);
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) throw Params.invalid("a transparent layer needs a .png savePath (JPEG has no transparency)");
            boolean split = Params.optBool(p, "split").orElse(false);
            if (split && layer != GuiLayer.GUI_TOOLTIP) throw Params.invalid("split needs layer 'gui+tooltip'");
            Integer scale = Params.optInt(p, "guiScale").orElse(null);
            if (scale != null && (scale < 1 || scale > 8)) throw Params.invalid("guiScale must be 1-8");
            return new Options(layer, split, Params.optBool(p, "trim").orElse(true), scale);
        }
    }

    public static Path splitPath(Path savePath, String part) {
        String n = savePath.getFileName().toString();
        int dot = n.lastIndexOf('.');
        String named = dot < 0 ? n + "." + part : n.substring(0, dot) + "." + part + n.substring(dot);
        return savePath.resolveSibling(named);
    }

    /** One image per part: [gui, tooltip] when split, else the layer. Fails with NO_SCREEN / NO_TOOLTIP. */
    public static CompletableFuture<List<BufferedImage>> grab(ClientScheduler s, Options o) {
        List<GuiLayer> parts = o.split() ? List.of(GuiLayer.GUI, GuiLayer.TOOLTIP) : List.of(o.layer());
        List<CompletableFuture<BufferedImage>> images = new ArrayList<>();
        return s.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget main = mc.gameRenderer.mainRenderTarget();
            ClientCompat compat = ClientCompat.get();
            for (GuiLayer part : parts) {
                // One canvas per part: the reads complete a frame later, so a shared canvas would be overwritten.
                RenderTarget canvas = part == GuiLayer.TOOLTIP && o.split() ? compat.guiCanvas(main.width, main.height, 1) : compat.guiCanvas(main.width, main.height, 0);
                ClientCompat.Drawn d = compat.drawGui(part, canvas, 0);
                if (part.screen() && !d.screen()) throw new AgentError("NO_SCREEN", "No screen is open.", "Open the menu first (gui_action, a command or the plugin's item), then capture.");
                if (part == GuiLayer.TOOLTIP && !d.tooltip()) throw new AgentError("NO_TOOLTIP", "No tooltip is shown.", "Hover an item first: gui_action {action:'hover', slot}.");
                CompletableFuture<BufferedImage> img = new CompletableFuture<>();
                int w = canvas.width, h = canvas.height;
                compat.readPixels(canvas, b -> {
                    try {
                        img.complete(AlphaFrame.fromPremultiplied(b, w, h));
                    } catch (RuntimeException e) {
                        img.completeExceptionally(e);
                    }
                });
                images.add(img);
            }
            return Boolean.TRUE;
        }).thenCompose(v -> CompletableFuture.allOf(images.toArray(CompletableFuture[]::new)))
                .thenApply(v -> images.stream().map(CompletableFuture::join).toList());
    }
}
```

This needs a second canvas for `split`: change `ClientCompat.guiCanvas(int, int)` to
`guiCanvas(int width, int height, int index)` (index 0 or 1; each index its own pooled target) in the interface, both
`GuiCanvas` classes (`RenderTarget[] canvases = new RenderTarget[2]`) and the Task 2 game test. `readPixels` must
copy the bytes inside the callback (`AlphaFrame.fromPremultiplied` does: it reads the buffer during the call).
The `NO_SCREEN` check uses the screen flag; `TOOLTIP` alone with no screen fails `NO_TOOLTIP` (no screen, no tooltip).

- [ ] **Step 4: Wire into ScreenshotHandler**

At the top of `capture`:

```java
        GuiCapture.Options gui = GuiCapture.Options.parse(p, savePath);
        if (gui != null && camera != null) throw Params.invalid("camera does not apply to a transparent layer (there is no world in it)");
```

In the `prepared` step, if `gui != null && gui.guiScale() != null`: remember `mc.options.guiScale().get()`, set the
new value and call `mc.resizeGui()`; add the old value to `Saved` and restore it (and `resizeGui()`) in `restore`.
Then, for `gui != null`, replace `.thenCompose(v -> grab(s))` with
`.thenCompose(v -> GuiCapture.grab(s, gui))` and the final step with `encodeLayers(images, maxSize, savePath, gui)`:

```java
    private static JsonElement encodeLayers(List<BufferedImage> images, int maxSize, String savePath, GuiCapture.Options o) {
        List<BufferedImage> out = images.stream().map(i -> o.trim() ? AlphaFrame.trim(i, 8) : i).toList();
        JsonObject r = new JsonObject();
        if (savePath != null) {
            Path target = Path.of(savePath).toAbsolutePath();
            List<Path> paths = o.split() ? List.of(GuiCapture.splitPath(target, "gui"), GuiCapture.splitPath(target, "tooltip")) : List.of(target);
            com.google.gson.JsonArray saved = new com.google.gson.JsonArray();
            for (int i = 0; i < out.size(); i++) {
                try {
                    if (paths.get(i).getParent() != null) Files.createDirectories(paths.get(i).getParent());
                    ImageIO.write(out.get(i), "png", paths.get(i).toFile());
                } catch (IOException e) {
                    throw new AgentError("SAVE_FAILED", "Could not write " + paths.get(i) + ": " + e.getMessage(), "Pass a writable savePath.");
                }
                saved.add(paths.get(i).toString());
            }
            r.addProperty("savedPath", saved.get(0).getAsString());
            if (o.split()) r.add("savedPaths", saved);
        }
        BufferedImage first = out.get(0);
        double scale = Math.min(1.0, maxSize / (double) Math.max(first.getWidth(), first.getHeight()));
        int w = Math.max(1, (int) Math.round(first.getWidth() * scale)), h = Math.max(1, (int) Math.round(first.getHeight() * scale));
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(first, 0, 0, w, h, null);
        g.dispose();
        r.addProperty("mime", "image/png");
        r.addProperty("data", Base64.getEncoder().encodeToString(png(small)));
        r.addProperty("width", w);
        r.addProperty("height", h);
        r.addProperty("fullWidth", first.getWidth());
        r.addProperty("fullHeight", first.getHeight());
        return r;
    }
```

(`png(BufferedImage)` is the handler's existing PNG encoder; check it keeps the alpha channel — `ImageIO` PNG does for
TYPE_INT_ARGB.) The `sectionsBuilt` wait stays only for world captures (`gui == null`); a GUI capture waits 2 frames.

- [ ] **Step 5: Unit test passes**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-fabric:test --tests '*GuiCaptureTest'`
Expected: PASS (3 tests).

- [ ] **Step 6: Game-test the tool path**

Add to `GuiLayerChecks.run` (screen open, book hovered):

```java
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("craftwire-gui");
        String save = dir.resolve("panel.png").toString().replace("\\", "\\\\");
        int scaleBefore = ctx.computeOnClient(mc -> mc.options.guiScale().get());
        var shot = Calls.call(ctx, "screenshot", "{\"layer\":\"gui+tooltip\",\"split\":true,\"guiScale\":3,\"savePath\":\"" + save + "\"}").getAsJsonObject();
        check(shot.getAsJsonArray("savedPaths").size() == 2, "split writes two files: " + shot);
        var guiPng = javax.imageio.ImageIO.read(dir.resolve("panel.gui.png").toFile());
        var tipPng = javax.imageio.ImageIO.read(dir.resolve("panel.tooltip.png").toFile());
        check(guiPng.getColorModel().hasAlpha() && tipPng.getColorModel().hasAlpha(), "PNG keeps alpha");
        check(guiPng.getRGB(0, 0) >>> 24 == 0, "trimmed image keeps a transparent margin");
        check(tipPng.getWidth() < guiPng.getWidth(), "the tooltip file holds only the tooltip");
        guiScaleIsRestoredEvenWhenClamped(ctx, scaleBefore);
        canvasFollowsWindowSize(ctx);
        check(Calls.error(ctx, "screenshot", "{\"layer\":\"gui\",\"savePath\":\"x.jpg\"}").code().equals("INVALID_PARAMS"), "jpg refused");
        Calls.call(ctx, "gui.action", "{\"action\":\"close\"}");
        check("NO_SCREEN".equals(Calls.error(ctx, "screenshot", "{\"layer\":\"gui\"}").code()), "no screen open");
```

```java
    /** Review Focus #3. */
    static void guiScaleIsRestoredEvenWhenClamped(ClientGameTestContext ctx, int before) throws Exception {
        Calls.call(ctx, "screenshot", "{\"layer\":\"gui\",\"guiScale\":8}");
        check(ctx.computeOnClient(mc -> mc.options.guiScale().get()) == before, "guiScale restored");
    }

    /** Review Focus #5: the canvas follows a window resize. */
    static void canvasFollowsWindowSize(ClientGameTestContext ctx) throws Exception {
        var a = Calls.call(ctx, "screenshot", "{\"layer\":\"gui\",\"trim\":false}").getAsJsonObject();
        int w = ctx.computeOnClient(mc -> mc.getWindow().getWidth()), h = ctx.computeOnClient(mc -> mc.getWindow().getHeight());
        ctx.runOnClient(mc -> mc.getWindow().setWindowed(w - 100, h - 60));
        ctx.waitTicks(5);
        var b = Calls.call(ctx, "screenshot", "{\"layer\":\"gui\",\"trim\":false}").getAsJsonObject();
        int bw = ctx.computeOnClient(mc -> mc.gameRenderer.mainRenderTarget().width);
        check(b.get("fullWidth").getAsInt() == bw && b.get("fullWidth").getAsInt() != a.get("fullWidth").getAsInt(), "canvas follows the window: " + a + " -> " + b);
        ctx.runOnClient(mc -> mc.getWindow().setWindowed(w, h));
        ctx.waitTicks(5);
    }
```

(`setWindowed` is the 26.2 `Window` method used by `client_process` window sizing — use the name the existing
`InputSettingsChecks`/window code uses.)

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :fabric-gametest-26.2:runClientGameTest` then `:fabric-gametest-26.3:runClientGameTest`
Expected: PASS on both.

- [ ] **Step 7: Commit**

```bash
git add agent-fabric
git commit -m "feat(fabric): screenshot layer gui/tooltip/gui+tooltip with split, trim and guiScale"
```

---

### Task 5: `record` with a transparent layer (`.mov` ProRes 4444, `.webm` VP9 alpha)

**Files:**
- Modify: `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/video/FfmpegArgs.java`
- Modify: `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/video/RecordParams.java`
- Modify: `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/video/Recorder.java`
- Modify: `agent-fabric/src/main/java/com/uxplima/craftwire/fabric/handlers/RecordHandler.java` (encoder check)
- Test: `agent-fabric/src/test/java/com/uxplima/craftwire/fabric/video/FfmpegArgsTest.java` (create if absent), `RecordParamsTest.java`
- Test: `agent-fabric/src/gametest/java/com/uxplima/craftwire/fabric/gametest/RecordChecks.java`

**Interfaces:**
- Consumes: `GuiLayer`, `GuiCapture.splitPath`, `ClientCompat.guiCanvas(w, h, index)`, `ClientCompat.drawGui`.
- Produces:
  - `enum FfmpegArgs.Container { MP4, MOV, WEBM; static Container of(Path) }`
  - `List<String> FfmpegArgs.video(String ffmpeg, int w, int h, VideoSettings s, Path out, Container c)` (the old overload delegates with `MP4`).
  - `List<String> FfmpegArgs.mux(String ffmpeg, Path video, Path wav, int kbps, Path out, Container c)`.
  - `static String FfmpegArgs.requiredEncoder(Container c)` — `"prores_ks"`, `"libvpx-vp9"`, or `"libx264"`.
  - `RecordParams` gains `GuiLayer layer` and `boolean split`.

- [ ] **Step 1: Failing unit tests**

```java
    @Test
    void movIsProRes4444WithAlpha() {
        var a = FfmpegArgs.video("ffmpeg", 1920, 1080, VideoSettings.parse(new com.google.gson.JsonObject()), java.nio.file.Path.of("o.mov"), FfmpegArgs.Container.MOV);
        assertTrue(String.join(" ", a).contains("-c:v prores_ks -profile:v 4444 -pix_fmt yuva444p10le"), String.join(" ", a));
        assertFalse(a.contains("+faststart"));
    }

    @Test
    void webmIsVp9WithAlpha() {
        var a = FfmpegArgs.video("ffmpeg", 1920, 1080, VideoSettings.parse(new com.google.gson.JsonObject()), java.nio.file.Path.of("o.webm"), FfmpegArgs.Container.WEBM);
        String s = String.join(" ", a);
        assertTrue(s.contains("-c:v libvpx-vp9 -pix_fmt yuva420p") && s.contains("-b:v 0") && s.contains("-crf "), s);
    }

    @Test
    void soundIsAacInMovAndOpusInWebm() {
        assertTrue(String.join(" ", FfmpegArgs.mux("f", java.nio.file.Path.of("v.mov"), java.nio.file.Path.of("a.wav"), 192, java.nio.file.Path.of("o.mov"), FfmpegArgs.Container.MOV)).contains("-c:a aac"));
        assertTrue(String.join(" ", FfmpegArgs.mux("f", java.nio.file.Path.of("v.webm"), java.nio.file.Path.of("a.wav"), 192, java.nio.file.Path.of("o.webm"), FfmpegArgs.Container.WEBM)).contains("-c:a libopus"));
    }

    /** Review Focus #4. */
    @Test
    void missingEncoderIsNamed() {
        assertEquals("prores_ks", FfmpegArgs.requiredEncoder(FfmpegArgs.Container.MOV));
        assertEquals("libvpx-vp9", FfmpegArgs.requiredEncoder(FfmpegArgs.Container.WEBM));
    }
```

`RecordParamsTest` additions:

```java
    @Test
    void transparentLayerNeedsMovOrWebm() {
        assertThrows(AgentError.class, () -> RecordParams.parse(json("{\"savePath\":\"a.mp4\",\"layer\":\"gui\"}"), null));
        assertEquals(GuiLayer.GUI, RecordParams.parse(json("{\"savePath\":\"a.mov\",\"layer\":\"gui\"}"), null).layer());
        assertNull(RecordParams.parse(json("{\"savePath\":\"a.mp4\"}"), null).layer());
        assertThrows(AgentError.class, () -> RecordParams.parse(json("{\"savePath\":\"a.mov\"}"), null));   // .mov only with a layer
        assertThrows(AgentError.class, () -> RecordParams.parse(json("{\"savePath\":\"a.mov\",\"layer\":\"gui\",\"split\":true}"), null));
    }
```

(and a camera-motion refusal, using the motion object the existing tests build: `RecordParams.parse(json("{\"savePath\":\"a.mov\",\"layer\":\"gui\"}"), motion)` throws.) Use the file's existing `json(...)` helper or add one.

- [ ] **Step 2: Run to see them fail**

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-fabric:test --tests '*FfmpegArgsTest' --tests '*RecordParamsTest'`
Expected: FAIL (compile errors).

- [ ] **Step 3: Implement FfmpegArgs**

```java
    public enum Container {
        MP4, MOV, WEBM;

        public static Container of(Path p) {
            String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            return n.endsWith(".mov") ? MOV : n.endsWith(".webm") ? WEBM : MP4;
        }
    }

    public static String requiredEncoder(Container c) {
        return switch (c) {
            case MOV -> "prores_ks";
            case WEBM -> "libvpx-vp9";
            case MP4 -> "libx264";
        };
    }

    public static List<String> video(String ffmpeg, int width, int height, VideoSettings s, Path out) {
        return video(ffmpeg, width, height, s, out, Container.MP4);
    }

    public static List<String> video(String ffmpeg, int width, int height, VideoSettings s, Path out, Container c) {
        int[] size = VideoSettings.outputSize(width, height, s.maxHeight());
        String filters = "vflip";
        if (size[1] != (height & ~1)) filters += ",scale=" + size[0] + ":" + size[1] + ":flags=lanczos";
        else if (size[0] != width || size[1] != height) filters += ",crop=" + size[0] + ":" + size[1] + ":0:0";
        // The GUI canvas holds premultiplied colour; ffmpeg's encoders expect straight alpha.
        if (c != Container.MP4) filters += ",unpremultiply=inplace=1";
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-framerate", Integer.toString(s.fps()), "-i", "-",
                "-vf", filters));
        switch (c) {
            case MOV -> a.addAll(List.of("-c:v", "prores_ks", "-profile:v", "4444", "-pix_fmt", "yuva444p10le", "-alpha_bits", "16", out.toString()));
            case WEBM -> a.addAll(List.of("-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-crf", Integer.toString(s.crf()), "-b:v", "0",
                    "-row-mt", "1", "-auto-alt-ref", "0", out.toString()));
            case MP4 -> {
                if (s.codec().equals("h265")) {
                    a.addAll(List.of("-c:v", "libx265", "-preset", s.speed(), "-crf", Integer.toString(s.crf()), "-tag:v", "hvc1", "-x265-params", "log-level=error"));
                } else {
                    a.addAll(List.of("-c:v", "libx264", "-preset", s.speed(), "-crf", Integer.toString(s.crf())));
                }
                a.addAll(List.of("-pix_fmt", "yuv420p", "-movflags", "+faststart", out.toString()));
            }
        }
        return a;
    }

    public static List<String> mux(String ffmpeg, Path video, Path wav, int audioKbps, Path out) {
        return mux(ffmpeg, video, wav, audioKbps, out, Container.MP4);
    }

    public static List<String> mux(String ffmpeg, Path video, Path wav, int audioKbps, Path out, Container c) {
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", video.toString(), "-i", wav.toString(),
                "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy"));
        a.addAll(c == Container.WEBM ? List.of("-c:a", "libopus", "-b:a", audioKbps + "k") : List.of("-c:a", "aac", "-b:a", audioKbps + "k"));
        if (c == Container.MP4) a.addAll(List.of("-movflags", "+faststart"));
        a.add(out.toString());
        return a;
    }
```

`-auto-alt-ref 0` is required for VP9 alpha in libvpx; the `unpremultiply` filter exists in ffmpeg ≥ 4.3. If the
game test in Step 6 shows ffmpeg rejects `unpremultiply` on `rgba` input, un-premultiply in Java in
`FrameWriter` instead (same maths as `AlphaFrame.fromPremultiplied`, in place on the frame bytes) and ledger it.

- [ ] **Step 4: RecordParams**

Replace the `.mp4` check with:

```java
        GuiLayer layer;
        try {
            layer = GuiLayer.parse(Params.optString(p, "layer").orElse(null));
        } catch (IllegalArgumentException e) {
            throw Params.invalid(e.getMessage());
        }
        String lower = save.toLowerCase(Locale.ROOT);
        if (layer == null && !lower.endsWith(".mp4")) throw Params.invalid("savePath must end in .mp4 (.mov or .webm only with a transparent layer)");
        if (layer != null && !(lower.endsWith(".mov") || lower.endsWith(".webm"))) {
            throw Params.invalid("a transparent layer needs .mov (ProRes 4444) or .webm (VP9); MP4 has no transparency");
        }
        if (layer != null && motion != null) throw Params.invalid("camera motions do not apply to a transparent layer (there is no world in it)");
        boolean split = Params.optBool(p, "split").orElse(false);
        if (split && layer != GuiLayer.GUI_TOOLTIP) throw Params.invalid("split needs layer 'gui+tooltip'");
```

and add `GuiLayer layer, boolean split` (and `Integer guiScale`, parsed like in `GuiCapture.Options`, 1-8) to the record and its
constructor call. Update every `new RecordParams(...)` in tests.

- [ ] **Step 5: Recorder**

- `prepare`: when `params.layer() != null`, check `mc.screen != null` for screen layers (`NO_SCREEN`) and, for
  `TOOLTIP`, nothing more (a tooltip may appear later — spec); apply `guiScale` like the screenshot handler and
  restore it in `restore(...)`; keep the HUD untouched (it is not drawn on the canvas).
- Start: `Container c = FfmpegArgs.Container.of(params.savePath())`. The temp video gets the container's extension
  (`videoTemp = Files.createTempFile("craftwire-", c == MOV ? ".mov" : c == WEBM ? ".webm" : ".mp4")`); the mux temp
  likewise. Before starting ffmpeg, run `ffmpeg -hide_banner -encoders` once (cache per ffmpeg path) and if
  `FfmpegArgs.requiredEncoder(c)` is not listed, fail `prepare` with
  `AgentError("FFMPEG_FAILED", "This ffmpeg has no " + enc + " encoder.", c == MOV ? "Record to .webm instead, or install a full ffmpeg build." : "Record to .mov instead, or install a full ffmpeg build.")`.
- With `split`, the session holds two tracks (process + writer + temp file), for parts `GUI` (canvas index 0, file
  `splitPath(save, "gui")`) and `TOOLTIP` (index 1, `splitPath(save, "tooltip")`); both get the same frame repeats and the same
  muxed sound. Without split: one track for `params.layer()` at canvas index 0.
- `onFrame` for a layer session: `RenderTarget main = mc.gameRenderer.mainRenderTarget()` is still used for the
  resize check; for each track, `canvas = compat.guiCanvas(main.width, main.height, track.index)`,
  `compat.drawGui(track.part, canvas, 0)`, then `capture(s, track, canvas, repeats)` (the existing `capture` with the
  writer and pending counters moved onto the track).
- Finish/mux/cleanup loop over tracks; the result lists `savedPaths` when split.

- [ ] **Step 6: Game test**

In `RecordChecks.run`, after the existing checks:

```java
        // Transparent GUI recordings.
        ctx.setScreen(() -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(Minecraft.getInstance().player));
        ctx.waitTicks(3);
        for (String ext : new String[] {"mov", "webm"}) {
            Path out = dir.resolve("gui." + ext);
            var r = Calls.call(ctx, "record", "{\"action\":\"start\",\"layer\":\"gui\",\"savePath\":\"" + out.toString().replace("\\", "\\\\") + "\",\"durationMs\":1500,\"wait\":true}").getAsJsonObject();
            check(java.nio.file.Files.size(out) > 1000, ext + " written: " + r);
            String fmt = probeAlpha(ffprobe, out);
            check(ext.equals("mov") ? fmt.contains("yuva444p") : fmt.contains("\"alpha_mode\":\"1\"") || fmt.contains("yuva420p"), ext + " has alpha: " + fmt);
            // One decoded frame: corner transparent, panel centre opaque.
            Path png = dir.resolve("gui-" + ext + ".png");
            run(ffmpeg.toString(), "-y", "-c:v", ext.equals("webm") ? "libvpx-vp9" : "prores", "-i", out.toString(), "-frames:v", "1", png.toString());
            var img = javax.imageio.ImageIO.read(png.toFile());
            check(img.getRGB(2, 2) >>> 24 < 8, ext + " corner transparent");
            check(img.getRGB(img.getWidth() / 2, img.getHeight() / 2) >>> 24 > 247, ext + " panel opaque");
        }
        screenClosingMidRecordingKeepsRecording(ctx, dir);
        check(Calls.error(ctx, "record", "{\"action\":\"start\",\"layer\":\"gui\",\"savePath\":\"" + dir.resolve("x.mp4").toString().replace("\\", "\\\\") + "\"}").getMessage().contains("MP4"), ".mp4 refused");
        Calls.call(ctx, "gui.action", "{\"action\":\"close\"}");
```

```java
    /** Review Focus #2. */
    static void screenClosingMidRecordingKeepsRecording(ClientGameTestContext ctx, Path dir) throws Exception {
        Path out = dir.resolve("closing.webm");
        Calls.call(ctx, "record", "{\"action\":\"start\",\"layer\":\"gui\",\"savePath\":\"" + out.toString().replace("\\", "\\\\") + "\",\"durationMs\":2000}");
        ctx.waitTicks(10);
        Calls.call(ctx, "gui.action", "{\"action\":\"close\"}");
        ctx.waitTicks(10);
        ctx.setScreen(() -> new net.minecraft.client.gui.screens.inventory.InventoryScreen(Minecraft.getInstance().player));
        var done = Calls.call(ctx, "record", "{\"action\":\"stop\"}").getAsJsonObject();
        check(java.nio.file.Files.size(out) > 1000, "recording survived a closed screen: " + done);
    }
```

Add next to the file's `probe` helper:

```java
    private static String probeAlpha(Path ffprobe, Path file) throws IOException, InterruptedException {
        return run(ffprobe.toString(), "-v", "error", "-show_entries", "stream=pix_fmt:stream_tags=alpha_mode", "-of", "json", file.toString());
    }
```

`run(String...)` is the file's existing helper; `Calls.error` returns the `AgentError` (`code()`, `getMessage()`). `ffmpeg`, `ffprobe` and `dir`
are the locals at the top of `RecordChecks.run`.

Run: `JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :agent-fabric:test` then `:fabric-gametest-26.2:runClientGameTest` and `:fabric-gametest-26.3:runClientGameTest`
Expected: PASS on all three.

- [ ] **Step 7: Commit**

```bash
git add agent-fabric
git commit -m "feat(fabric): record transparent GUI layers to ProRes 4444 .mov and VP9 .webm"
```

---

### Task 6: Hub — arguments, validation, doc topic, budgets

**Files:**
- Modify: `hub/src/tools/client-tools.ts` (screenshot, record)
- Modify: `hub/src/resources.ts` (`DOC_TOPICS` gets `"gui-capture"`)
- Create: `docs/gui-capture.md`
- Modify: `hub/tools/copy-docs.mjs` only if it does not copy every `DOC_TOPICS` entry (it does since M10 — check)
- Test: `hub/test/client-tools.test.ts` (or the file that already tests screenshot/record forwarding — `grep -ln "\"screenshot\"" hub/test`), `hub/test/tool-budget.test.ts` (unchanged limits must still pass)

**Interfaces:**
- Consumes: the client's new params (`layer`, `split`, `trim`, `guiScale`) and results (`savedPaths`).
- Produces: `screenshot {layer?, split?, trim?, guiScale?}`, `record start {layer?, split?, guiScale?}`.

- [ ] **Step 1: Failing hub tests**

```ts
  it("screenshot forwards a transparent layer and refuses jpg for it", async () => {
    let seen: Record<string, unknown> = {};
    agent.onRequest("screenshot", (p) => { seen = p; return { mime: "image/png", data: "iVBORw0KGgo=", width: 1, height: 1, fullWidth: 1, fullHeight: 1, savedPaths: ["a.gui.png", "a.tooltip.png"] }; });
    const r = await hub.call("screenshot", { layer: "gui+tooltip", split: true, guiScale: 4, savePath: "a.png" });
    expect(seen).toMatchObject({ layer: "gui+tooltip", split: true, guiScale: 4 });
    expect(JSON.stringify(r)).toContain("a.tooltip.png");
    await expect(hub.call("screenshot", { layer: "gui", savePath: "a.jpg" })).rejects.toThrow(/png/);
    await expect(hub.call("screenshot", { layer: "gui", split: true })).rejects.toThrow(/gui\+tooltip/);
  });

  it("record refuses mp4 and camera motions for a transparent layer", async () => {
    await expect(hub.call("record", { action: "start", layer: "gui", savePath: "a.mp4" })).rejects.toThrow(/\.mov|\.webm/);
    await expect(hub.call("record", { action: "start", layer: "gui", savePath: "a.mov", camera: { action: "orbit", center: { x: 0, y: 0, z: 0 }, radius: 5, durationMs: 1000 } })).rejects.toThrow(/camera/);
  });
```

Put them in the describe block that already starts a hub and a fake client agent for screenshot (follow its setup;
`hub.call` rejecting on a tool error is how that file asserts errors — mirror its existing error assertions).

- [ ] **Step 2: Run to see them fail**

Run: `cd hub && npm run build && npx vitest run test/client-tools.test.ts`
Expected: FAIL (unknown keys stripped / no refusal).

- [ ] **Step 3: Implement**

In `screenshot`'s schema add:

```ts
      layer: z.enum(["world", "gui", "tooltip", "gui+tooltip"]).default("world"),
      split: z.boolean().optional(),
      trim: z.boolean().optional(),
      guiScale: z.number().int().min(1).max(8).optional(),
```

and before forwarding:

```ts
      if (args.layer !== "world") {
        if (args.savePath && /\.jpe?g$/i.test(args.savePath)) throw new CraftwireError("INVALID_PARAMS", "A transparent layer needs a .png savePath (JPEG has no transparency).", "Use .png.");
        if (args.camera) throw new CraftwireError("INVALID_PARAMS", "camera does not apply to a transparent layer.", "Leave camera out.");
      }
      if (args.split && args.layer !== "gui+tooltip") throw new CraftwireError("INVALID_PARAMS", "split needs layer 'gui+tooltip'.", "Pass layer:'gui+tooltip'.");
```

Return `savedPaths` in `meta` when present. Description (keep it short):
`"... layer gui|tooltip|gui+tooltip: transparent PNG of the open menu/tooltip (split: two files). Details: craftwire://docs/gui-capture."`

In `record`'s schema add `layer`, `split`, `guiScale` the same way; before forwarding, for a non-world layer refuse
`.mp4` (`"A transparent layer needs .mov (ProRes 4444) or .webm (VP9); MP4 has no transparency."`) and `camera`.
Description: replace "Record an MP4" with "Record a video" and append `layer gui|tooltip|gui+tooltip: transparent .mov/.webm.`

- [ ] **Step 4: Doc topic and budgets**

Write `docs/gui-capture.md` (layers, `split`, `trim`, `guiScale`, the format table from the spec with which editor reads
which file, the hover-then-capture example below, the 4K recipe, errors), add `"gui-capture"` to `DOC_TOPICS`.

```jsonc
gui_action {action:"hover", slot:13}
screenshot {layer:"gui+tooltip", split:true, guiScale:4, savePath:"shots/kit.png"}
record {action:"start", layer:"gui+tooltip", savePath:"videos/kit.mov", durationMs:8000, wait:true}
```

Run: `cd hub && npm run build && npx vitest run && node tools/measure-tools.mjs && node tools/measure-tools.mjs --profile build`
Expected: all tests PASS; totals within 28,000 / 10,500 and every tool ≤ 1,800. If a budget is exceeded, shorten
`screenshot`/`record` descriptions first (move wording to `docs/gui-capture.md`), then other long descriptions, and
re-run; ledger the before/after totals.

- [ ] **Step 5: Commit**

```bash
git add hub docs/gui-capture.md
git commit -m "feat(hub): transparent GUI layers for screenshot and record; docs/gui-capture"
```

---

### Task 7: Docs, skill, README, acceptance

**Files:**
- Modify: `claude-plugin/skills/minecraft-promo-shots/SKILL.md` (a "GUI and lore panels" section)
- Modify: `README.md` (one item under "Record promo videos" with an image)
- Create: `docs/images/gui-capture.png` (made by Craftwire in this task)
- Create: `docs/acceptance/gui-capture.md`
- Modify: `docs/video.md` (one line pointing to `docs/gui-capture.md`)

- [ ] **Step 1: Skill and docs text**

Skill section (≤ 1,200 chars): when a creator wants a menu or an item's lore as a separate panel, open the menu,
`gui_action hover` the item, `screenshot {layer:"gui+tooltip", split:true, guiScale:4, savePath:"….png"}`; for motion,
`record {layer:"gui+tooltip", savePath:"….mov"}` (Premiere/DaVinci/AE/Final Cut) or `.webm` (web/OBS); a 4K hidden client
(`client_process {windowSize:"3840x2160"}`) gives crisp panels. Run `cd hub && npx vitest run test/tool-budget.test.ts`
(skill size limits) — Expected: PASS.

- [ ] **Step 2: Acceptance run**

On a throwaway setup (scratchpad, separate `CRAFTWIRE_HOME`, as for M10 — `craftwire serve` + a Paper 26.2 server with
the test fixture plugin, and a hidden client from `client_process` at 3840x2160): open the fixture's menu
(`/cwfixture menu`), hover an item with lore, take `screenshot {layer:"gui+tooltip", split:true, guiScale:4}`,
record 5 s of hovering across items to `.mov` and `.webm` (move the hover with `gui_action` during the recording).
Composite the PNGs over a checkerboard and over a game screenshot (ImageMagick or a small Java/Python script in the
scratchpad) and look at them: crisp edges, text shadows intact, translucent tooltip background, no dark fringe. Decode
a frame of each video the same way and look at it. Save the composite over the screenshot as
`docs/images/gui-capture.png`.

- [ ] **Step 3: Write the record and README item**

`docs/acceptance/gui-capture.md`: environment, the calls, file sizes, `ffprobe` pixel formats, the images, anything
that missed a goal and what was done. README: under "Record promo videos", a short paragraph ("Need the menu or an
item's lore as its own panel? `layer:"gui+tooltip"` gives it on a transparent background…") and the image.

- [ ] **Step 4: Run every suite**

```bash
JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew build :agent-paper:integrationTest
JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" ./gradlew :fabric-gametest-26.2:runClientGameTest :fabric-gametest-26.3:runClientGameTest
cd hub && npm run build && npx vitest run && JAVA_HOME="C:/Program Files/Java/jdk-26.0.1" PATH="/c/Program Files/Java/jdk-26.0.1/bin:$PATH" npm run test:e2e
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add README.md docs claude-plugin
git commit -m "docs: GUI capture guide, promo skill section, acceptance record"
```

- [ ] **Step 6: Final review and hand-off**

Self-review of the whole GUI-capture range (the user's rule: no subagents), ledger it, list rulings and deferred
minors in Turkish, and ask before merging `feat/m10-build` or pushing.
