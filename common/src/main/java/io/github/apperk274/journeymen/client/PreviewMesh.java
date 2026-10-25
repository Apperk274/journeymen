package io.github.apperk274.journeymen.client;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.network.PreviewBlocks;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class PreviewMesh implements AutoCloseable {

    private static final int MAX_QUADS = 1_000_000;
    private static final Direction[] DIRECTIONS = Direction.values();

    private final ScreenPayloads.SchematicInfo info;
    private final CompletableFuture<PreviewBlocks> blocks;
    private final Map<Rotation, Mesh> meshes = new EnumMap<>(Rotation.class);
    private final Map<Rotation, CompletableFuture<Built>> builds = new EnumMap<>(Rotation.class);
    private boolean outlineOnly;
    private boolean closed;

    PreviewMesh(ScreenPayloads.SchematicInfo info, List<byte[]> data) {
        this.info = info;
        this.blocks = CompletableFuture.supplyAsync(() -> {
            try {
                return PreviewBlocks.decode(info.size(), info.palette().size(), data);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.backgroundExecutor());
    }

    boolean isFor(ScreenPayloads.SchematicInfo other) {
        return info == other;
    }

    boolean isOutlineOnly() {
        return outlineOnly;
    }

    boolean isReady(Rotation rotation) {
        return outlineOnly || meshes.containsKey(rotation);
    }

    void render(Rotation rotation, double x, double y, double z) {
        if (closed || outlineOnly) {
            return;
        }
        Vector3f camera = new Vector3f((float) -x, (float) -y, (float) -z);
        Mesh mesh = meshes.get(rotation);
        if (mesh == null) {
            mesh = poll(rotation, camera);
            if (mesh == null) {
                return;
            }
        }
        mesh.resort(camera);
        mesh.draw(x, y, z);
    }

    @Nullable
    private Mesh poll(Rotation rotation, Vector3f camera) {
        CompletableFuture<Built> build = builds.get(rotation);
        if (build == null) {
            List<BlockState> palette = info.palette();
            builds.put(rotation, blocks.thenApplyAsync(decoded -> Built.of(decoded, palette, rotation, camera), Util.backgroundExecutor()));
            return null;
        }
        if (!build.isDone()) {
            return null;
        }
        builds.remove(rotation);
        Built built;
        try {
            built = build.join();
        } catch (RuntimeException e) {
            Constants.LOG.warn("Could not build the preview of {}", info.file(), e);
            outlineOnly = true;
            return null;
        }
        if (built == null) {
            outlineOnly = true;
            return null;
        }
        Mesh mesh = built.upload();
        meshes.put(rotation, mesh);
        int quads = meshes.values().stream().mapToInt(m -> m.quads).sum();
        if (quads > MAX_QUADS) {
            meshes.entrySet().removeIf(entry -> {
                if (entry.getKey() != rotation) {
                    entry.getValue().close();
                    return true;
                }
                return false;
            });
        }
        return mesh;
    }

    @Override
    public void close() {
        closed = true;
        meshes.values().forEach(Mesh::close);
        meshes.clear();
        builds.values().forEach(build -> build.thenAccept(built -> {
            if (built != null) {
                built.close();
            }
        }));
        builds.clear();
    }

    private record Built(ByteBufferBuilder vertices, @Nullable ByteBufferBuilder indices, @Nullable MeshData mesh,
                         @Nullable MeshData.SortState sort, int quads, Vector3f sortedFrom) {

        @Nullable
        static Built of(PreviewBlocks blocks, List<BlockState> palette, Rotation rotation, Vector3f camera) {
            Minecraft minecraft = Minecraft.getInstance();
            BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
            List<BlockState> rotated = palette.stream().map(state -> state.rotate(rotation)).toList();
            Rotation inverse = rotation == Rotation.CLOCKWISE_90 ? Rotation.COUNTERCLOCKWISE_90
                    : rotation == Rotation.COUNTERCLOCKWISE_90 ? Rotation.CLOCKWISE_90 : rotation;
            BlockGetter getter = new RotatedBlocks(blocks, rotated, inverse);
            RandomSource random = RandomSource.create();
            byte[] faces = new byte[blocks.count()];
            long quads = 0;
            for (int i = 0; i < blocks.count(); i++) {
                BlockState state = rotated.get(blocks.state(i));
                if (state.getRenderShape() != RenderShape.MODEL) {
                    continue;
                }
                BlockPos pos = new BlockPos(blocks.x(i), blocks.y(i), blocks.z(i)).rotate(rotation);
                BakedModel model = dispatcher.getBlockModel(state);
                long seed = state.getSeed(pos);
                int mask = 1 << DIRECTIONS.length;
                for (Direction direction : DIRECTIONS) {
                    random.setSeed(seed);
                    List<BakedQuad> list = model.getQuads(state, direction, random);
                    if (!list.isEmpty() && visible(state, getter, pos, direction)) {
                        mask |= 1 << direction.ordinal();
                        quads += list.size();
                    }
                }
                random.setSeed(seed);
                quads += model.getQuads(state, null, random).size();
                faces[i] = (byte) mask;
                if (quads > MAX_QUADS) {
                    return null;
                }
            }
            ByteBufferBuilder vertices = new ByteBufferBuilder((int) Math.max(256, quads * 4 * DefaultVertexFormat.BLOCK.getVertexSize()));
            try {
                BufferBuilder builder = new BufferBuilder(vertices, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                Tints tints = new Tints(minecraft.getBlockColors());
                PoseStack poseStack = new PoseStack();
                for (int i = 0; i < blocks.count(); i++) {
                    int mask = faces[i];
                    if (mask == 0) {
                        continue;
                    }
                    BlockState state = rotated.get(blocks.state(i));
                    BlockPos pos = new BlockPos(blocks.x(i), blocks.y(i), blocks.z(i)).rotate(rotation);
                    BakedModel model = dispatcher.getBlockModel(state);
                    long seed = state.getSeed(pos);
                    poseStack.pushPose();
                    poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
                    for (Direction direction : DIRECTIONS) {
                        if ((mask & 1 << direction.ordinal()) != 0) {
                            random.setSeed(seed);
                            emit(builder, poseStack.last(), state, model.getQuads(state, direction, random), tints);
                        }
                    }
                    random.setSeed(seed);
                    emit(builder, poseStack.last(), state, model.getQuads(state, null, random), tints);
                    poseStack.popPose();
                }
                MeshData mesh = builder.build();
                if (mesh == null) {
                    return new Built(vertices, null, null, null, 0, camera);
                }
                int count = mesh.drawState().vertexCount() / 4;
                ByteBufferBuilder indices = new ByteBufferBuilder(Math.max(256, count * 6 * mesh.drawState().indexType().bytes));
                MeshData.SortState sort = mesh.sortQuads(indices, VertexSorting.byDistance(camera));
                return new Built(vertices, indices, mesh, sort, count, camera);
            } catch (RuntimeException | Error e) {
                vertices.close();
                throw e;
            }
        }

        private static boolean visible(BlockState state, BlockGetter getter, BlockPos pos, Direction direction) {
            try {
                return Block.shouldRenderFace(state, getter, pos, direction, pos.relative(direction));
            } catch (RuntimeException e) {
                return true;
            }
        }

        private static void emit(BufferBuilder builder, PoseStack.Pose pose, BlockState state, List<BakedQuad> quads, Tints tints) {
            for (BakedQuad quad : quads) {
                float shade = quad.isShade() ? shade(quad.getDirection()) : 1.0F;
                int color = quad.isTinted() ? tints.get(state, quad.getTintIndex()) : -1;
                builder.putBulkData(pose, quad, (color >> 16 & 255) / 255.0F * shade, (color >> 8 & 255) / 255.0F * shade,
                        (color & 255) / 255.0F * shade, 0.5F, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            }
        }

        private static float shade(Direction direction) {
            return switch (direction) {
                case DOWN -> 0.5F;
                case UP -> 1.0F;
                case NORTH, SOUTH -> 0.8F;
                case WEST, EAST -> 0.6F;
            };
        }

        Mesh upload() {
            if (mesh == null) {
                close();
                return new Mesh(null, null, 0, sortedFrom);
            }
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(mesh);
            VertexBuffer.unbind();
            close();
            return new Mesh(buffer, sort, quads, sortedFrom);
        }

        void close() {
            if (mesh != null) {
                mesh.close();
            }
            vertices.close();
            if (indices != null) {
                indices.close();
            }
        }
    }

    private static final class Mesh {

        @Nullable
        private final VertexBuffer buffer;
        @Nullable
        private final MeshData.SortState sort;
        private final int quads;
        private Vector3f sortedFrom;
        @Nullable
        private CompletableFuture<Sorted> sorting;

        Mesh(@Nullable VertexBuffer buffer, @Nullable MeshData.SortState sort, int quads, Vector3f sortedFrom) {
            this.buffer = buffer;
            this.sort = sort;
            this.quads = quads;
            this.sortedFrom = sortedFrom;
        }

        void resort(Vector3f camera) {
            if (buffer == null || sort == null) {
                return;
            }
            if (sorting != null) {
                if (!sorting.isDone()) {
                    return;
                }
                CompletableFuture<Sorted> done = sorting;
                sorting = null;
                try {
                    Sorted sorted = done.join();
                    buffer.bind();
                    buffer.uploadIndexBuffer(sorted.result());
                    VertexBuffer.unbind();
                    sorted.builder().close();
                } catch (RuntimeException e) {
                    Constants.LOG.warn("Could not sort the schematic preview", e);
                }
            }
            if (camera.distanceSquared(sortedFrom) > 1.0F) {
                Vector3f from = new Vector3f(camera);
                sortedFrom = from;
                MeshData.SortState state = sort;
                int bytes = quads * 6 * state.indexType().bytes;
                sorting = CompletableFuture.supplyAsync(() -> {
                    ByteBufferBuilder builder = new ByteBufferBuilder(bytes);
                    return new Sorted(builder, state.buildSortedIndexBuffer(builder, VertexSorting.byDistance(from)));
                }, Util.backgroundExecutor());
            }
        }

        void draw(double x, double y, double z) {
            if (buffer == null) {
                return;
            }
            RenderType type = RenderType.translucent();
            type.setupRenderState();
            ShaderInstance shader = RenderSystem.getShader();
            if (shader != null) {
                Matrix4f modelView = RenderSystem.getModelViewMatrix();
                Uniform offset = shader.CHUNK_OFFSET;
                if (offset != null) {
                    offset.set((float) x, (float) y, (float) z);
                } else {
                    modelView = new Matrix4f(modelView).translate((float) x, (float) y, (float) z);
                }
                buffer.bind();
                buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), shader);
                VertexBuffer.unbind();
                if (offset != null) {
                    offset.set(0.0F, 0.0F, 0.0F);
                }
            }
            type.clearRenderState();
        }

        void close() {
            if (buffer != null) {
                buffer.close();
            }
            if (sorting != null) {
                sorting.thenAccept(Sorted::close);
                sorting = null;
            }
        }
    }

    private record Sorted(ByteBufferBuilder builder, @Nullable ByteBufferBuilder.Result result) {

        void close() {
            if (result != null) {
                result.close();
            }
            builder.close();
        }
    }

    private static final class Tints {

        private final BlockColors colors;
        private final Long2IntOpenHashMap cache = new Long2IntOpenHashMap();

        Tints(BlockColors colors) {
            this.colors = colors;
        }

        int get(BlockState state, int tintIndex) {
            long key = (long) Block.getId(state) << 32 | tintIndex & 0xFFFFFFFFL;
            if (cache.containsKey(key)) {
                return cache.get(key);
            }
            int color;
            try {
                color = colors.getColor(state, null, null, tintIndex);
            } catch (RuntimeException e) {
                color = -1;
            }
            cache.put(key, color);
            return color;
        }
    }

    private record RotatedBlocks(PreviewBlocks blocks, List<BlockState> states, Rotation inverse) implements BlockGetter {

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockPos local = pos.rotate(inverse);
            int index = blocks.stateAt(local.getX(), local.getY(), local.getZ());
            return index < 0 ? Blocks.AIR.defaultBlockState() : states.get(index);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getHeight() {
            return 4096;
        }

        @Override
        public int getMinBuildHeight() {
            return -2048;
        }
    }
}
