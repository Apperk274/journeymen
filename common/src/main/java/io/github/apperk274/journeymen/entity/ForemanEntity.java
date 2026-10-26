package io.github.apperk274.journeymen.entity;

import io.github.apperk274.journeymen.build.AssignedChest;
import io.github.apperk274.journeymen.build.BuildJob;
import io.github.apperk274.journeymen.build.Crew;
import io.github.apperk274.journeymen.build.CrewWork;
import io.github.apperk274.journeymen.build.ItemStores;
import io.github.apperk274.journeymen.build.PreCheck;
import io.github.apperk274.journeymen.command.Status;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.menu.LinkModeService;
import io.github.apperk274.journeymen.schematic.SchematicFiles;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class ForemanEntity extends JourneymanEntity {

    public static final double POST_DISTANCE = 8.0;
    private static final double POST_RETURN_DISTANCE = 5.0;
    private static final int POST_OUTSIDE = 2;
    private static final int MAX_CHESTS = 16;
    private static final int RECRUIT_INTERVAL = 20;
    private static final double PROGRESS_BAR_RANGE = 64.0;

    private final List<AssignedChest> chests = new ArrayList<>();
    @Nullable
    private BuildJob job;
    @Nullable
    private ServerBossEvent progressBar;
    private final Map<String, Long> relayed = new HashMap<>();
    private boolean returning;

    public ForemanEntity(EntityType<? extends ForemanEntity> type, Level level) {
        super(type, level);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            setDropChance(slot, 0.0F);
        }
    }

    @Override
    public JourneymanRegistry.Kind kind() {
        return JourneymanRegistry.Kind.FOREMAN;
    }

    @Override
    public boolean isWorking() {
        return getJob() != null;
    }

    @Nullable
    @Override
    public BuildJob getJob() {
        MinecraftServer server = level().getServer();
        if (server == null) {
            return job;
        }
        JourneymanRegistry registry = JourneymanRegistry.get(server);
        if (job != null && !registry.hasJob(job.getId())) {
            job = null;
            relayed.clear();
        }
        if (job == null) {
            job = registry.jobOf(getUUID());
        }
        return job;
    }

    public List<AssignedChest> getChests() {
        return List.copyOf(chests);
    }

    @Nullable
    public String addChest(BlockPos pos, AssignedChest.Role role) {
        if (chests.size() >= MAX_CHESTS) {
            return "A foreman can have at most " + MAX_CHESTS + " chests.";
        }
        if (ItemStores.at(level(), pos) == null) {
            return "There is no chest or other container at " + pos.toShortString() + ".";
        }
        AssignedChest existing = chestAt(pos);
        if (existing != null) {
            return "That chest is already assigned as a " + existing.role().id() + " chest.";
        }
        chests.add(new AssignedChest(pos.immutable(), role));
        return null;
    }

    @Nullable
    public AssignedChest chestAt(BlockPos pos) {
        BlockPos otherHalf = Crew.otherChestHalf(level(), pos);
        for (AssignedChest chest : chests) {
            if (chest.pos().equals(pos) || chest.pos().equals(otherHalf)) {
                return chest;
            }
        }
        return null;
    }

    public boolean removeChest(BlockPos pos) {
        return chests.removeIf(chest -> chest.pos().equals(pos));
    }

    public List<JourneymanRegistry.Entry> crewEntries() {
        MinecraftServer server = level().getServer();
        return server == null ? List.of() : JourneymanRegistry.get(server).crewOf(getUUID());
    }

    public List<BuilderEntity> loadedCrew() {
        MinecraftServer server = level().getServer();
        List<BuilderEntity> crew = new ArrayList<>();
        if (server == null) {
            return crew;
        }
        JourneymanRegistry registry = JourneymanRegistry.get(server);
        for (JourneymanRegistry.Entry entry : registry.crewOf(getUUID())) {
            if (registry.loaded(server, entry.id()) instanceof BuilderEntity builder && getUUID().equals(builder.getForemanId())) {
                crew.add(builder);
            }
        }
        return crew;
    }

    public List<BuilderEntity> loadedCrewHere() {
        return loadedCrew().stream().filter(builder -> builder.level() == level()).toList();
    }

    public static boolean canTakePart(BuilderEntity builder, ResourceKey<Level> dimension, BoundingBox area) {
        return builder.isAlive() && builder.level().dimension().equals(dimension)
                && PreCheck.horizontalDistance(builder.blockPosition(), area) <= JourneymenConfig.MAX_SITE_DISTANCE.get();
    }

    public boolean isCrewFull() {
        int max = JourneymenConfig.MAX_CREW_SIZE.get();
        return max > 0 && crewEntries().size() >= max;
    }

    public void beginJob(BuildJob newJob) {
        job = newJob;
        relayed.clear();
        stopIdleOrders();
        returning = false;
        getNavigation().stop();
        recruit(newJob);
    }

    private void recruit(BuildJob current) {
        MinecraftServer server = level().getServer();
        if (server == null) {
            return;
        }
        JourneymanRegistry registry = JourneymanRegistry.get(server);
        List<BuilderEntity> joining = new ArrayList<>();
        for (BuilderEntity builder : loadedCrew()) {
            if (builder.getJob() == null
                    && (current.hasMember(builder.getUUID()) || canTakePart(builder, current.getDimension(), current.getArea()))) {
                registry.joinJob(current, builder.getUUID());
                joining.add(builder);
            }
        }
        joining.forEach(builder -> builder.beginJob(current));
    }

    public void cancelJob() {
        BuildJob current = getJob();
        if (current != null) {
            endJob(current);
        }
    }

    public void endJob(BuildJob current) {
        MinecraftServer server = level().getServer();
        for (BuilderEntity member : Crew.loaded(server, current)) {
            member.leaveWork();
        }
        if (server != null) {
            JourneymanRegistry.get(server).removeJob(current.getId());
        }
        if (job == current) {
            job = null;
        }
        relayed.clear();
        returning = false;
        removeProgressBar();
        getNavigation().stop();
    }

    public void finishJob(BuildJob done) {
        List<BlockPos> problems = new ArrayList<>(done.getUnclearable());
        done.getSkippedPositions().stream().filter(pos -> !problems.contains(pos)).forEach(problems::add);
        if (!problems.isEmpty()) {
            notifyOwner("I couldn't build " + problems.size() + " block(s) of " + done.getFile() + ", e.g. at " + problems.get(0).toShortString() + ".");
        }
        notifyOwner("I finished building " + done.getFile() + ".");
        endJob(done);
    }

    public void stopLoading(BuildJob loadingJob, String problem) {
        notifyOwner("I stopped working on " + loadingJob.getFile() + " because the file can't be loaded anymore: " + problem);
        endJob(loadingJob);
    }

    public void relay(String message) {
        long now = level().getGameTime();
        long reminderTicks = JourneymenConfig.REMINDER_INTERVAL_SECONDS.get() * 20L;
        relayed.values().removeIf(tick -> now - tick >= reminderTicks);
        if (relayed.containsKey(message)) {
            return;
        }
        relayed.put(message, now);
        notifyOwner(message);
    }

    @Override
    protected void tickWork() {
        BuildJob current = getJob();
        if (current == null) {
            return;
        }
        if (current.getSchematic() == null) {
            loadSchematic(current);
        }
        if (tickCount % RECRUIT_INTERVAL == 0) {
            recruit(current);
        }
        keepPost(current);
    }

    private void loadSchematic(BuildJob loadingJob) {
        if (loadingJob.isLoading()) {
            return;
        }
        loadingJob.setLoading(true);
        MinecraftServer server = level().getServer();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return SchematicFiles.load(loadingJob.getFile());
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, Util.backgroundExecutor())
                .whenCompleteAsync((schematic, error) -> {
                    loadingJob.setLoading(false);
                    if (!JourneymanRegistry.get(server).hasJob(loadingJob.getId())) {
                        return;
                    }
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        stopLoading(loadingJob, cause.getMessage());
                        return;
                    }
                    loadingJob.setLoaded(schematic, null);
                }, server);
    }

    private void keepPost(BuildJob current) {
        if (!current.getDimension().equals(level().dimension())) {
            return;
        }
        BoundingBox area = current.getArea();
        BlockPos post = insidePost(area);
        if (post != null) {
            if (tickCount % 20 == 0 || getNavigation().isDone()) {
                walkTowards(Vec3.atBottomCenterOf(post));
            }
            return;
        }
        double distance = PreCheck.horizontalDistance(blockPosition(), area);
        if (distance > POST_DISTANCE) {
            returning = true;
        }
        if (!returning) {
            return;
        }
        if (distance <= POST_RETURN_DISTANCE) {
            returning = false;
            getNavigation().stop();
            return;
        }
        if (tickCount % 20 == 0 || getNavigation().isDone()) {
            double x = Math.max(area.minX(), Math.min(area.maxX(), getX()));
            double z = Math.max(area.minZ(), Math.min(area.maxZ(), getZ()));
            walkTowards(new Vec3(x + 0.5, getY(), z + 0.5));
        }
    }

    @Nullable
    private BlockPos insidePost(BoundingBox area) {
        BlockPos pos = blockPosition();
        if (pos.getX() < area.minX() || pos.getX() > area.maxX() || pos.getZ() < area.minZ() || pos.getZ() > area.maxZ()) {
            return null;
        }
        BlockPos edge = outsideOf(area, true);
        if (edge == null) {
            return null;
        }
        int dx = Integer.signum(edge.getX() < area.minX() ? -1 : edge.getX() > area.maxX() ? 1 : 0);
        int dz = Integer.signum(edge.getZ() < area.minZ() ? -1 : edge.getZ() > area.maxZ() ? 1 : 0);
        return edge.offset(dx * (POST_OUTSIDE - 1), 0, dz * (POST_OUTSIDE - 1));
    }

    @Nullable
    @Override
    protected StuckRecovery.Goal workGoal() {
        BuildJob current = getJob();
        if (current == null || !current.getDimension().equals(level().dimension())) {
            return null;
        }
        BlockPos post = insidePost(current.getArea());
        if (post != null) {
            return new StuckRecovery.Goal(StuckRecovery.Kind.SPOT, post, post, Math.sqrt(distanceToSqr(Vec3.atBottomCenterOf(post))),
                    "a post outside the site of " + current.getFile());
        }
        return returning ? siteGoal(current, false) : null;
    }

    @Nullable
    @Override
    protected StuckRecovery.Goal unstickWorkGoal() {
        BuildJob current = getJob();
        return current == null ? null : siteGoal(current, true);
    }

    @Override
    protected int workDone() {
        return 0;
    }

    @Override
    protected void afterEscape() {
        returning = false;
    }

    public record JobStatus(BuildJob.Phase phase, String text, @Nullable String pauseReason, int done, int total) {

        public float fraction() {
            return total == 0 ? 0.0F : (float) done / total;
        }
    }

    public JobStatus jobStatus() {
        BuildJob current = getJob();
        if (current == null) {
            return new JobStatus(BuildJob.Phase.WALKING, isFollowing() ? "following its owner" : getComeTarget() != null ? "walking to you" : "idle",
                    null, 0, 0);
        }
        if (current.getSchematic() == null) {
            return new JobStatus(BuildJob.Phase.LOADING, "loading", null, 0, 0);
        }
        List<BuilderEntity> members = Crew.loaded(level().getServer(), current);
        CrewWork shared = current.getCrewWork();
        BuildJob.Phase phase;
        String text;
        int done = 0;
        int all = 0;
        if (shared.getPlacing() != null) {
            done = shared.getPlacing().getPlaced();
            all = done + shared.getPlacing().getRemaining();
            phase = BuildJob.Phase.PLACING;
            text = "placing " + done + "/" + all;
        } else if (shared.getClearing() != null) {
            done = shared.getClearing().getCleared();
            all = done + shared.getClearing().getRemaining();
            phase = BuildJob.Phase.CLEARING;
            text = "clearing " + done + "/" + all;
        } else if (members.isEmpty()) {
            phase = BuildJob.Phase.WALKING;
            text = "waiting for builders";
        } else {
            phase = BuildJob.Phase.WALKING;
            text = "walking to the site";
        }
        for (BuilderEntity member : members) {
            if (member.getWork().getPhase() == BuildJob.Phase.PAUSED) {
                return new JobStatus(BuildJob.Phase.PAUSED, "paused", member.getWork().getPauseReason(), done, all);
            }
        }
        return new JobStatus(phase, text, null, done, all);
    }

    private void updateProgressBar() {
        ServerPlayer ownerPlayer = getOwnerPlayer();
        BuildJob current = getJob();
        boolean show = current != null && isAlive() && ownerPlayer != null && ownerPlayer.level() == level()
                && ownerPlayer.distanceToSqr(this) <= PROGRESS_BAR_RANGE * PROGRESS_BAR_RANGE;
        if (!show) {
            removeProgressBar();
            return;
        }
        JobStatus status = jobStatus();
        Component title = Component.literal(getJourneymanName() + "'s crew · " + current.getFile() + " · " + status.text());
        BossEvent.BossBarColor color = status.phase() == BuildJob.Phase.PAUSED ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.GREEN;
        if (progressBar == null) {
            progressBar = new ServerBossEvent(title, color, BossEvent.BossBarOverlay.PROGRESS);
        }
        progressBar.setName(title);
        progressBar.setColor(color);
        progressBar.setProgress(Math.max(0.0F, Math.min(1.0F, status.fraction())));
        if (!progressBar.getPlayers().contains(ownerPlayer)) {
            progressBar.removeAllPlayers();
            progressBar.addPlayer(ownerPlayer);
        }
    }

    private void removeProgressBar() {
        if (progressBar != null) {
            progressBar.removeAllPlayers();
            progressBar = null;
        }
    }

    @Override
    protected void tickPeriodic() {
        if (tickCount % 10 == 0) {
            updateProgressBar();
        }
        if (tickCount % 20 == 0) {
            ServerPlayer ownerPlayer = getOwnerPlayer();
            if (ownerPlayer != null) {
                LinkModeService.tick(ownerPlayer);
            }
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide) {
            if (canBeCommandedBy(player) && player instanceof ServerPlayer serverPlayer) {
                LinkModeService.finish(serverPlayer, this);
                Status.foreman(serverPlayer.server, this).forEach(serverPlayer::sendSystemMessage);
            } else {
                player.displayClientMessage(Component.literal(getJourneymanName() + " works for someone else."), true);
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    protected void onGone(boolean died) {
        removeProgressBar();
        BuildJob current = getJob();
        List<BuilderEntity> crew = loadedCrew();
        List<String> names = crewEntries().stream().map(JourneymanRegistry.Entry::name).toList();
        StringBuilder notice = new StringBuilder(died ? "I died" : "I'm gone");
        if (current != null) {
            BlockPos origin = current.getOrigin();
            String options = current.getOptions().describe();
            notice.append(", so ").append(current.getFile()).append(" at ").append(origin.toShortString()).append(", rotation ")
                    .append(current.getRotation().getSerializedName()).append(", options ").append(options)
                    .append(" stopped. Give the same build to another foreman to continue from what is built: /journeymen foreman build <foreman> ")
                    .append(current.getFile().contains(" ") || current.getFile().contains("/") ? "\"" + current.getFile() + "\"" : current.getFile())
                    .append(" ").append(origin.getX()).append(" ").append(origin.getY()).append(" ").append(origin.getZ()).append(" ")
                    .append(current.getRotation().getSerializedName()).append(options.equals("none") ? "" : " " + options).append(".");
            endJob(current);
        } else {
            notice.append(".");
        }
        if (!names.isEmpty()) {
            notice.append(" ").append(Crew.list(names)).append(names.size() == 1 ? " has" : " have")
                    .append(" no foreman now; another foreman can adopt ").append(names.size() == 1 ? "it" : "them")
                    .append(" with /journeymen foreman adopt <foreman> <builder>.");
        }
        notifyOwner(notice.toString());
        for (BuilderEntity builder : crew) {
            builder.becomeOrphan();
        }
        chests.clear();
    }

    @Override
    public void remove(RemovalReason reason) {
        removeProgressBar();
        super.remove(reason);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        ListTag chestList = new ListTag();
        chests.forEach(chest -> chestList.add(chest.save()));
        tag.put("Chests", chestList);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        chests.clear();
        ListTag chestList = tag.getList("Chests", Tag.TAG_COMPOUND);
        for (int i = 0; i < chestList.size(); i++) {
            AssignedChest chest = AssignedChest.load(chestList.getCompound(i));
            if (chest != null) {
                chests.add(chest);
            }
        }
    }
}
