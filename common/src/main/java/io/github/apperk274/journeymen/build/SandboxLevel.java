package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class SandboxLevel implements LevelAccessor {

    private final ServerLevel level;
    @Nullable
    private final BlockPos onlyPos;
    @Nullable
    private final BlockState onlyState;
    private final RandomSource random = RandomSource.create(0L);

    private SandboxLevel(ServerLevel level, @Nullable BlockPos onlyPos, @Nullable BlockState onlyState) {
        this.level = level;
        this.onlyPos = onlyPos;
        this.onlyState = onlyState;
    }

    public static boolean survivesAlone(ServerLevel level, BlockPos pos, BlockState state) {
        try {
            return state.canSurvive(new SandboxLevel(level, pos, state), pos);
        } catch (RuntimeException e) {
            Constants.LOG.debug("Could not test {} on its own at {}", state, pos, e);
            return false;
        }
    }

    public static BlockState shapedAlone(ServerLevel level, BlockPos pos, BlockState state) {
        return shaped(new SandboxLevel(level, pos, state), pos, state);
    }

    public static BlockState shapedHere(ServerLevel level, BlockPos pos, BlockState state) {
        return shaped(new SandboxLevel(level, null, null), pos, state);
    }

    private static BlockState shaped(SandboxLevel sandbox, BlockPos pos, BlockState state) {
        try {
            return Block.updateFromNeighbourShapes(state, sandbox, pos);
        } catch (RuntimeException e) {
            Constants.LOG.debug("Could not shape {} at {}", state, pos, e);
            return state;
        }
    }

    private boolean isolated() {
        return onlyPos != null;
    }

    @Override
    public BlockState getBlockState(BlockPos at) {
        if (isolated()) {
            return at.equals(onlyPos) ? onlyState : Blocks.AIR.defaultBlockState();
        }
        return level.getBlockState(at);
    }

    @Override
    public FluidState getFluidState(BlockPos at) {
        if (isolated()) {
            return at.equals(onlyPos) ? onlyState.getFluidState() : Fluids.EMPTY.defaultFluidState();
        }
        return level.getFluidState(at);
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos at) {
        return isolated() ? null : level.getBlockEntity(at);
    }

    @Override
    public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos at, BlockEntityType<T> type) {
        return isolated() ? Optional.empty() : level.getBlockEntity(at, type);
    }

    @Override
    public boolean isStateAtPosition(BlockPos at, Predicate<BlockState> predicate) {
        return predicate.test(getBlockState(at));
    }

    @Override
    public boolean isFluidAtPosition(BlockPos at, Predicate<FluidState> predicate) {
        return predicate.test(getFluidState(at));
    }

    @Override
    public boolean setBlock(BlockPos at, BlockState state, int flags, int recursionLeft) {
        return false;
    }

    @Override
    public boolean removeBlock(BlockPos at, boolean isMoving) {
        return false;
    }

    @Override
    public boolean destroyBlock(BlockPos at, boolean dropBlock, @Nullable Entity entity, int recursionLeft) {
        return false;
    }

    @Override
    public long nextSubTickCount() {
        return 0L;
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelData getLevelData() {
        return level.getLevelData();
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos at) {
        return level.getCurrentDifficultyAt(at);
    }

    @Nullable
    @Override
    public MinecraftServer getServer() {
        return level.getServer();
    }

    @Override
    public ChunkSource getChunkSource() {
        return level.getChunkSource();
    }

    @Override
    public RandomSource getRandom() {
        return random;
    }

    @Override
    public void playSound(@Nullable Player player, BlockPos at, SoundEvent sound, SoundSource source, float volume, float pitch) {
    }

    @Override
    public void addParticle(ParticleOptions particle, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed) {
    }

    @Override
    public void levelEvent(@Nullable Player player, int type, BlockPos at, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 position, GameEvent.Context context) {
    }

    @Override
    public List<Entity> getEntities(@Nullable Entity except, AABB area, Predicate<? super Entity> predicate) {
        return level.getEntities(except, area, predicate);
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB area, Predicate<? super T> predicate) {
        return level.getEntities(test, area, predicate);
    }

    @Override
    public List<? extends Player> players() {
        return level.players();
    }

    @Override
    public long dayTime() {
        return level.dayTime();
    }

    @Override
    public BlockPos getHeightmapPos(Heightmap.Types type, BlockPos at) {
        return level.getHeightmapPos(type, at);
    }

    @Nullable
    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean require) {
        return level.getChunk(x, z, status, require);
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return level.hasChunk(x, z);
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return level.getHeight(type, x, z);
    }

    @Override
    public int getSkyDarken() {
        return level.getSkyDarken();
    }

    @Override
    public BiomeManager getBiomeManager() {
        return level.getBiomeManager();
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return level.getUncachedNoiseBiome(x, y, z);
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public int getSeaLevel() {
        return level.getSeaLevel();
    }

    @Override
    public DimensionType dimensionType() {
        return level.dimensionType();
    }

    @Override
    public RegistryAccess registryAccess() {
        return level.registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return level.enabledFeatures();
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return level.getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return level.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos at, ColorResolver resolver) {
        return level.getBlockTint(at, resolver);
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return level.getMinBuildHeight();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return level.getWorldBorder();
    }

    @Nullable
    @Override
    public BlockGetter getChunkForCollisions(int x, int z) {
        return this;
    }

    @Override
    public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB area) {
        return List.of();
    }
}
