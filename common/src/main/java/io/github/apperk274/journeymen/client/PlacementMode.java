package io.github.apperk274.journeymen.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
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
    private static boolean corner;
    private static boolean allowBreakingEnchanted;
    @Nullable
    private static BlockPos origin;
    private static long lastHint;
    private static long lastScroll;

    private PlacementMode() {
    }

    public static void start(int builderId, ScreenPayloads.SchematicInfo info, boolean corner, boolean allowBreakingEnchanted) {
        PlacementMode.active = true;
        PlacementMode.builderId = builderId;
        PlacementMode.info = info;
        PlacementMode.corner = corner;
        PlacementMode.allowBreakingEnchanted = allowBreakingEnchanted;
        PlacementMode.lastHint = 0;
    }

    public static void resume(int builderId, ScreenPayloads.SchematicInfo info, Rotation rotation, boolean corner, boolean allowBreakingEnchanted) {
        start(builderId, info, corner, allowBreakingEnchanted);
        PlacementMode.rotation = rotation;
    }

    public static void stop() {
        active = false;
        info = null;
        origin = null;
        rotation = Rotation.NONE;
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
        String options = (corner ? "corner " : "") + (allowBreakingEnchanted ? "allow-breaking-enchanted" : "");
        Services.NETWORK.sendToServer(new ScreenPayloads.BuilderAction(builderId, "check",
                info.file() + "\n" + rotation.getSerializedName() + "\n" + options.strip(), origin));
        return true;
    }

    private static void hint(boolean force) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = minecraft.level == null ? 0 : minecraft.level.getGameTime();
        if (minecraft.player != null && (force || now - lastHint > 40)) {
            lastHint = now;
            minecraft.player.displayClientMessage(Component.literal("Placing " + (info == null ? "" : info.file()) + " (" + rotation.getSerializedName()
                    + ") · sneak + scroll: rotate · right-click: confirm · sneak + right-click: cancel").withStyle(ChatFormatting.AQUA), true);
        }
    }

    public static void render(Camera camera) {
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
        BlockPos base = corner ? BlockPos.ZERO : info.offset();
        BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
        VertexConsumer ghost = new TranslucentConsumer(buffers.getBuffer(RenderType.translucent()));
        List<BlockState> palette = info.palette();
        int[] blocks = info.blocks();
        for (int i = 0; i + 3 < blocks.length; i += 4) {
            BlockPos world = toWorld(base, blocks[i], blocks[i + 1], blocks[i + 2]);
            BlockState state = palette.get(blocks[i + 3]).rotate(rotation);
            poseStack.pushPose();
            poseStack.translate(world.getX() - cameraPos.x, world.getY() - cameraPos.y, world.getZ() - cameraPos.z);
            dispatcher.getModelRenderer().renderModel(poseStack.last(), ghost, state, dispatcher.getBlockModel(state), 1.0F, 1.0F, 1.0F,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
        buffers.endBatch(RenderType.translucent());
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
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (ScreenPayloads.ChestEntry chest : link.chests()) {
            AABB box = new AABB(chest.pos()).inflate(0.02).move(-cameraPos.x, -cameraPos.y, -cameraPos.z);
            if (chest.supply()) {
                LevelRenderer.renderLineBox(poseStack, lines, box, 0.2F, 1.0F, 0.2F, 1.0F);
            } else {
                LevelRenderer.renderLineBox(poseStack, lines, box, 1.0F, 0.6F, 0.1F, 1.0F);
            }
        }
        buffers.endBatch(RenderType.lines());
    }

    private static BlockPos toWorld(BlockPos base, int x, int y, int z) {
        return origin.offset(new BlockPos(base.getX() + x, base.getY() + y, base.getZ() + z).rotate(rotation));
    }

    private record TranslucentConsumer(VertexConsumer delegate) implements VertexConsumer {

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha / 2);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }
}
