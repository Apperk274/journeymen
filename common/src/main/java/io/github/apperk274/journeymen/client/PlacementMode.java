package io.github.apperk274.journeymen.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.apperk274.journeymen.build.BuildOptions;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class PlacementMode {

    private static boolean active;
    private static int builderId;
    @Nullable
    private static ScreenPayloads.SchematicInfo info;
    private static Rotation rotation = Rotation.NONE;
    private static String options = "";
    @Nullable
    private static BlockPos origin;
    private static long lastHint;
    private static long lastScroll;
    @Nullable
    private static PreviewMesh mesh;

    private PlacementMode() {
    }

    public static void start(int builderId, ScreenPayloads.SchematicInfo info, String options) {
        if (mesh != null && !mesh.isFor(info)) {
            freeMesh();
        }
        PlacementMode.active = true;
        PlacementMode.builderId = builderId;
        PlacementMode.info = info;
        PlacementMode.options = options.strip();
        PlacementMode.lastHint = 0;
    }

    public static void resume(int builderId, ScreenPayloads.SchematicInfo info, Rotation rotation, String options) {
        start(builderId, info, options);
        PlacementMode.rotation = rotation;
    }

    public static void stop() {
        active = false;
        info = null;
        origin = null;
        rotation = Rotation.NONE;
        freeMesh();
    }

    private static void freeMesh() {
        PreviewMesh old = mesh;
        mesh = null;
        if (old != null) {
            if (RenderSystem.isOnRenderThread()) {
                old.close();
            } else {
                RenderSystem.recordRenderCall(old::close);
            }
        }
    }

    public static boolean isPreviewReady() {
        return active && info != null && (!info.complete() || mesh != null && mesh.isReady(rotation));
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean onScroll(double delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!active || minecraft.screen != null || minecraft.player == null || !minecraft.player.isShiftKeyDown() || delta == 0) {
            return false;
        }
        long now = Util.getMillis();
        if (now - lastScroll >= 150) {
            lastScroll = now;
            rotation = rotation.getRotated(delta > 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90);
            hint(true);
        }
        return true;
    }

    public static boolean onUse() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!active || minecraft.player == null || info == null) {
            return false;
        }
        if (minecraft.player.isShiftKeyDown()) {
            stop();
            minecraft.player.displayClientMessage(Component.literal("Placement cancelled."), true);
            return true;
        }
        if (origin == null) {
            minecraft.player.displayClientMessage(Component.literal("Look at a block to place the schematic there.").withStyle(ChatFormatting.RED), true);
            return true;
        }
        Services.NETWORK.sendToServer(new ScreenPayloads.BuilderAction(builderId, "check",
                info.file() + "\n" + rotation.getSerializedName() + "\n" + options, origin));
        return true;
    }

    private static void hint(boolean force) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = minecraft.level == null ? 0 : minecraft.level.getGameTime();
        if (minecraft.player != null && (force || now - lastHint > 40)) {
            lastHint = now;
            boolean outline = info != null && (!info.complete() || mesh != null && mesh.isOutlineOnly());
            minecraft.player.displayClientMessage(Component.literal("Placing " + (info == null ? "" : info.file()) + " (" + rotation.getSerializedName()
                    + (outline ? ", outline only" : "") + ") · sneak + scroll: rotate · right-click: confirm · sneak + right-click: cancel")
                    .withStyle(ChatFormatting.AQUA), true);
        }
    }

    public static void render(Camera camera) {
        PreviewBenchmark.frame();
        Minecraft minecraft = Minecraft.getInstance();
        PoseStack poseStack = new PoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        renderLinkedChests(poseStack, camera, buffers);
        if (!active || info == null || minecraft.level == null) {
            return;
        }
        HitResult hit = minecraft.hitResult;
        origin = hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
                ? blockHit.getBlockPos().relative(blockHit.getDirection())
                : null;
        hint(false);
        if (origin == null) {
            return;
        }
        Vec3 cameraPos = camera.getPosition();
        BlockPos base = List.of(options.split(" ")).contains(BuildOptions.CORNER) ? BlockPos.ZERO : info.offset();
        if (info.complete()) {
            if (mesh == null) {
                List<byte[]> data = ClientState.previewData(info);
                if (data != null) {
                    mesh = new PreviewMesh(info, data);
                }
            }
            if (mesh != null) {
                BlockPos anchor = origin.offset(base.rotate(rotation));
                mesh.render(rotation, anchor.getX() - cameraPos.x, anchor.getY() - cameraPos.y, anchor.getZ() - cameraPos.z);
            }
        }
        BlockPos a = toWorld(base, 0, 0, 0);
        BlockPos b = toWorld(base, info.size().getX() - 1, info.size().getY() - 1, info.size().getZ() - 1);
        AABB box = new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1)
                .move(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        LevelRenderer.renderLineBox(poseStack, buffers.getBuffer(RenderType.lines()), box, 0.3F, 0.8F, 1.0F, 1.0F);
        buffers.endBatch(RenderType.lines());
    }

    private static void renderLinkedChests(PoseStack poseStack, Camera camera, MultiBufferSource.BufferSource buffers) {
        ScreenPayloads.LinkMode link = ClientState.link;
        if (link == null) {
            return;
        }
        Vec3 cameraPos = camera.getPosition();
        Level level = Minecraft.getInstance().level;
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (ScreenPayloads.ChestEntry chest : link.chests()) {
            AABB box = chestBox(level, chest.pos()).inflate(0.02).move(-cameraPos.x, -cameraPos.y, -cameraPos.z);
            if (chest.supply()) {
                LevelRenderer.renderLineBox(poseStack, lines, box, 0.2F, 1.0F, 0.2F, 1.0F);
            } else {
                LevelRenderer.renderLineBox(poseStack, lines, box, 1.0F, 0.6F, 0.1F, 1.0F);
            }
        }
        buffers.endBatch(RenderType.lines());
    }

    private static AABB chestBox(@Nullable Level level, BlockPos pos) {
        AABB box = new AABB(pos);
        if (level == null || !level.isLoaded(pos)) {
            return box;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return box.minmax(new AABB(pos.relative(ChestBlock.getConnectedDirection(state))));
        }
        return box;
    }

    private static BlockPos toWorld(BlockPos base, int x, int y, int z) {
        return origin.offset(new BlockPos(base.getX() + x, base.getY() + y, base.getZ() + z).rotate(rotation));
    }
}
