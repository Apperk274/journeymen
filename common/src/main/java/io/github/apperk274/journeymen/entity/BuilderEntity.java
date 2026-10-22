package io.github.apperk274.journeymen.entity;

import com.mojang.authlib.GameProfile;
import io.github.apperk274.journeymen.entity.ai.BuilderMoveGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.UUID;

public class BuilderEntity extends PathfinderMob {

    public static final int INVENTORY_SIZE = 27;
    private static final int REGISTRY_SYNC_INTERVAL = 40;
    private static final int REGENERATION_INTERVAL = 80;
    private static final double LONG_WALK_DISTANCE = 40.0;

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE) {
        @Override
        public boolean stillValid(Player player) {
            return BuilderEntity.this.isAlive() && player.distanceToSqr(BuilderEntity.this) <= 64.0;
        }
    };

    @Nullable
    private UUID owner;
    @Nullable
    private String ownerName;
    private boolean following;
    @Nullable
    private BlockPos comeTarget;

    public BuilderEntity(EntityType<? extends BuilderEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        GroundPathNavigation navigation = new GroundPathNavigation(this, level);
        navigation.setCanOpenDoors(true);
        navigation.setCanPassDoors(true);
        navigation.setCanFloat(true);
        return navigation;
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        goalSelector.addGoal(2, new BuilderMoveGoal(this));
        goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(4, new RandomLookAroundGoal(this));
    }

    @Nullable
    public UUID getOwner() {
        return owner;
    }

    @Nullable
    public ServerPlayer getOwnerPlayer() {
        MinecraftServer server = level().getServer();
        return server == null || owner == null ? null : server.getPlayerList().getPlayer(owner);
    }

    public String getBuilderName() {
        Component name = getCustomName();
        return name == null ? getType().getDescription().getString() : name.getString();
    }

    public boolean canBeCommandedBy(Player player) {
        return owner == null || owner.equals(player.getUUID()) || player.hasPermissions(2);
    }

    public Container getInventory() {
        return inventory;
    }

    public boolean isFollowing() {
        return following;
    }

    public void setFollowing(boolean following) {
        this.following = following;
        this.comeTarget = null;
        getNavigation().stop();
    }

    @Nullable
    public BlockPos getComeTarget() {
        return comeTarget;
    }

    public void comeTo(BlockPos target) {
        this.following = false;
        this.comeTarget = target.immutable();
    }

    public void clearComeTarget() {
        this.comeTarget = null;
    }

    public void walkTowards(Vec3 target) {
        if (distanceToSqr(target) > LONG_WALK_DISTANCE * LONG_WALK_DISTANCE) {
            Vec3 step = LandRandomPos.getPosTowards(this, 32, 10, target);
            if (step != null) {
                getNavigation().moveTo(step.x, step.y, step.z, 1.0);
                return;
            }
        }
        getNavigation().moveTo(target.x, target.y, target.z, 1.0);
    }

    public void assignOwner(ServerPlayer player, String name) {
        this.owner = player.getUUID();
        this.ownerName = player.getGameProfile().getName();
        setName(name);
        syncRegistry();
    }

    @Nullable
    public String rename(String newName) {
        String problem = BuilderNames.validate(newName);
        if (problem != null) {
            return problem;
        }
        MinecraftServer server = level().getServer();
        if (owner != null && server != null && BuilderRegistry.get(server).isNameTaken(owner, newName, getUUID())) {
            return "There is already a builder named " + newName + ".";
        }
        setName(newName);
        syncRegistry();
        return null;
    }

    public InteractionResult renameWithNameTag(ItemStack stack, Player player) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        if (name == null) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!canBeCommandedBy(player)) {
            player.displayClientMessage(Component.literal(getBuilderName() + " works for someone else."), true);
            return InteractionResult.CONSUME;
        }
        String problem = rename(name.getString());
        if (problem != null) {
            player.displayClientMessage(Component.literal(problem), true);
            return InteractionResult.CONSUME;
        }
        stack.shrink(1);
        return InteractionResult.CONSUME;
    }

    private void setName(String name) {
        setCustomName(Component.literal(name));
        setCustomNameVisible(true);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide) {
            if (canBeCommandedBy(player)) {
                player.openMenu(new SimpleMenuProvider(
                        (id, playerInventory, p) -> ChestMenu.threeRows(id, playerInventory, inventory),
                        getDisplayName()));
            } else {
                player.displayClientMessage(Component.literal(getBuilderName() + " works for someone else."), true);
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        MinecraftServer server = level().getServer();
        if (server != null && !server.isPvpAllowed() && source.getEntity() instanceof Player player
                && owner != null && !owner.equals(player.getUUID())) {
            return false;
        }
        return super.hurt(source, amount);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide || !isAlive()) {
            return;
        }
        pickUpNearbyItems();
        if (tickCount % REGENERATION_INTERVAL == 0 && getHealth() < getMaxHealth()) {
            heal(1.0F);
        }
        if (tickCount % REGISTRY_SYNC_INTERVAL == 0) {
            syncRegistry();
        }
    }

    private void pickUpNearbyItems() {
        for (ItemEntity item : level().getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(1.0, 0.5, 1.0))) {
            if (item.isRemoved() || item.hasPickUpDelay() || item.getItem().isEmpty()) {
                continue;
            }
            ItemStack stack = item.getItem();
            int before = stack.getCount();
            ItemStack remainder = inventory.addItem(stack.copy());
            int taken = before - remainder.getCount();
            if (taken <= 0) {
                continue;
            }
            take(item, taken);
            if (remainder.isEmpty()) {
                item.discard();
            } else {
                item.setItem(remainder);
            }
        }
    }

    private void syncRegistry() {
        MinecraftServer server = level().getServer();
        if (server == null || owner == null || isRemoved()) {
            return;
        }
        BuilderRegistry registry = BuilderRegistry.get(server);
        if (ownerName == null) {
            ownerName = server.getProfileCache() == null ? null : server.getProfileCache().get(owner).map(GameProfile::getName).orElse(null);
        }
        String name = hasCustomName() ? getBuilderName() : null;
        if (name == null || BuilderNames.validate(name) != null || registry.isNameTaken(owner, name, getUUID())) {
            name = BuilderNames.random(getRandom(), new HashSet<>(registry.namesOf(owner)));
            setName(name);
        }
        registry.put(getUUID(), owner, ownerName == null ? owner.toString() : ownerName, name, level().dimension(), blockPosition());
    }

    @Override
    public void remove(RemovalReason reason) {
        MinecraftServer server = level().getServer();
        if (server != null && reason.shouldDestroy()) {
            BuilderRegistry.get(server).remove(getUUID());
        }
        super.remove(reason);
    }

    @Override
    public void die(DamageSource source) {
        boolean wasDead = dead;
        super.die(source);
        ServerPlayer ownerPlayer = getOwnerPlayer();
        if (!wasDead && dead && ownerPlayer != null) {
            ownerPlayer.sendSystemMessage(getCombatTracker().getDeathMessage());
        }
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
    protected void dropEquipment() {
        super.dropEquipment();
        Containers.dropContents(level(), this, inventory);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        if (ownerName != null) {
            tag.putString("OwnerName", ownerName);
        }
        tag.putBoolean("Following", following);
        CompoundTag inventoryTag = new CompoundTag();
        ContainerHelper.saveAllItems(inventoryTag, inventory.getItems(), registryAccess());
        tag.put("Inventory", inventoryTag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ownerName = tag.contains("OwnerName") ? tag.getString("OwnerName") : null;
        following = tag.getBoolean("Following");
        NonNullList<ItemStack> items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag.getCompound("Inventory"), items, registryAccess());
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, items.get(slot));
        }
    }
}
