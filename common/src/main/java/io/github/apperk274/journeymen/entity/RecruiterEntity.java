package io.github.apperk274.journeymen.entity;

import io.github.apperk274.journeymen.block.ModBlocks;
import io.github.apperk274.journeymen.block.RecruitersStallBlock;
import io.github.apperk274.journeymen.hire.HireService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public class RecruiterEntity extends PathfinderMob {

    @Nullable
    private BlockPos stall;

    public RecruiterEntity(EntityType<? extends RecruiterEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    @Nullable
    public BlockPos getStall() {
        return stall;
    }

    public void setStall(BlockPos stall) {
        this.stall = stall.immutable();
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level() instanceof ServerLevel serverLevel && tickCount % 20 == 0
                && (stall == null || !RecruitersStallBlock.isIntactCore(serverLevel, stall, ModBlocks.RECRUITERS_STALL))) {
            serverLevel.sendParticles(ParticleTypes.POOF, getX(), getY() + 1.0, getZ(), 12, 0.3, 0.5, 0.3, 0.02);
            discard();
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            HireService.openScreen(serverPlayer, this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (stall != null) {
            tag.putIntArray("Stall", new int[]{stall.getX(), stall.getY(), stall.getZ()});
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        int[] pos = tag.getIntArray("Stall");
        stall = pos.length == 3 ? new BlockPos(pos[0], pos[1], pos[2]) : null;
    }
}
