package io.github.apperk274.journeymen.entity;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.build.AssignedChest;
import io.github.apperk274.journeymen.build.BuildJob;
import io.github.apperk274.journeymen.build.Crew;
import io.github.apperk274.journeymen.build.CrewWork;
import io.github.apperk274.journeymen.build.ItemStores;
import io.github.apperk274.journeymen.build.OwnerRights;
import io.github.apperk274.journeymen.build.PostSearch;
import io.github.apperk274.journeymen.build.PreCheck;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.menu.ForemanMenu;
import io.github.apperk274.journeymen.menu.ForemanScreenService;
import io.github.apperk274.journeymen.menu.LinkModeService;
import io.github.apperk274.journeymen.platform.Services;
import io.github.apperk274.journeymen.schematic.SchematicFiles;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class ForemanEntity extends JourneymanEntity {

    public static final double POST_DISTANCE = 8.0;
    private static final double POST_RETURN_DISTANCE = 5.0;
    private static final int POST_SEARCH_TICKS = 200;
    private static final int RETURN_GIVE_UP_TICKS = 1200;
    private static final int RETURN_RETRY_TICKS = 1200;
    private static final int RECRUIT_INTERVAL = 20;

    private final List<AssignedChest> chests = new ArrayList<>();
    @Nullable
    private BuildJob job;
    private final Map<String, Long> relayed = new HashMap<>();
    private boolean returning;
    private boolean away;
    @Nullable
    private BlockPos post;
    private int postSearchedAt = Integer.MIN_VALUE / 2;
    private double returnBest = Double.MAX_VALUE;
    private int returnStalledTicks;
    private int returnRetryAt;
    private long chestsRefreshedAt = Long.MIN_VALUE;

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
        refreshChests();
        return List.copyOf(chests);
    }

    public List<AssignedChest> chestsIn(Level level) {
        refreshChests();
        return chests.stream().filter(chest -> chest.isIn(level.dimension())).toList();
    }

    public List<AssignedChest> usableChestsIn(Level level) {
        return chestsIn(level).stream().filter(chest -> ownerMayOpen(level, chest.pos())).toList();
    }

    public boolean ownerMayOpen(Level level, BlockPos pos) {
        return getOwner() == null || !(level instanceof ServerLevel serverLevel)
                || OwnerRights.mayOpenContainer(serverLevel, getOwner(), getOwnerName(), pos);
    }

    private void refreshChests() {
        Level here = level();
        MinecraftServer server = here.getServer();
        if (here.isClientSide || server == null || here.getGameTime() == chestsRefreshedAt) {
            return;
        }
        chestsRefreshedAt = here.getGameTime();
        Set<String> seen = new HashSet<>();
        List<AssignedChest> refreshed = new ArrayList<>();
        for (AssignedChest chest : chests) {
            Level level = server.getLevel(chest.dimension());
            AssignedChest current = chest;
            if (level != null && level.isLoaded(chest.pos())) {
                BlockPos other = Crew.otherChestHalf(level, chest.pos());
                if (other != null) {
                    current = chest.withPlace(chest.pos(), other.immutable());
                } else if (ItemStores.at(level, chest.pos()) != null) {
                    current = chest.withPlace(chest.pos(), null);
                } else if (chest.pair() != null && level.isLoaded(chest.pair()) && ItemStores.at(level, chest.pair()) != null) {
                    current = chest.withPlace(chest.pair(), null);
                }
            }
            long key = level != null && level.isLoaded(current.pos()) ? Crew.chestKey(level, current.pos()) : current.pos().asLong();
            if (seen.add(current.dimension().location() + " " + key)) {
                refreshed.add(current);
            }
        }
        chests.clear();
        chests.addAll(refreshed);
    }

    @Nullable
    public String addChest(Level level, BlockPos pos, AssignedChest.Role role) {
        int max = JourneymenConfig.MAX_CHESTS.get();
        if (chests.size() >= max) {
            return "A foreman can have at most " + max + " chests (max_chests in the Journeymen config).";
        }
        if (ItemStores.isLocked(level, pos)) {
            return "The chest at " + pos.toShortString() + " is locked, so " + getJourneymanName() + "'s builders couldn't open it.";
        }
        if (ItemStores.at(level, pos) == null) {
            return "There is no chest or other container at " + pos.toShortString() + ".";
        }
        AssignedChest existing = chestAt(level, pos);
        if (existing != null) {
            return "That chest is already assigned as a " + existing.role().id() + " chest.";
        }
        BlockPos other = Crew.otherChestHalf(level, pos);
        if (getOwner() == null) {
            return getJourneymanName() + " has no owner.";
        }
        if (!ownerMayOpen(level, pos)) {
            return getOwnerName() + " isn't allowed to open the chest at " + pos.toShortString() + " (spawn protection or a claim), so "
                    + getJourneymanName() + "'s builders couldn't use it.";
        }
        chests.add(new AssignedChest(pos.immutable(), role, other == null ? null : other.immutable(), level.dimension()));
        chestsRefreshedAt = Long.MIN_VALUE;
        return null;
    }

    @Nullable
    public AssignedChest chestAt(Level level, BlockPos pos) {
        refreshChests();
        BlockPos otherHalf = Crew.otherChestHalf(level, pos);
        for (AssignedChest chest : chests) {
            if (chest.isIn(level.dimension()) && (chest.pos().equals(pos) || chest.pos().equals(otherHalf) || pos.equals(chest.pair()))) {
                return chest;
            }
        }
        return null;
    }

    public boolean removeChest(ResourceKey<Level> dimension, BlockPos pos) {
        MinecraftServer server = level().getServer();
        Level level = server == null ? null : server.getLevel(dimension);
        AssignedChest chest = null;
        if (level != null) {
            chest = chestAt(level, pos);
        } else {
            for (AssignedChest candidate : chests) {
                if (candidate.isIn(dimension) && (candidate.pos().equals(pos) || pos.equals(candidate.pair()))) {
                    chest = candidate;
                }
            }
        }
        if (chest == null) {
            return false;
        }
        chests.remove(chest);
        chestsRefreshedAt = Long.MIN_VALUE;
        return true;
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
        return builder.isAlive() && builder.level().dimension().equals(dimension) && Crew.isTicking(builder)
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
        away = false;
        post = null;
        returnRetryAt = 0;
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
            if (builder.getJob() == null && builder.level().dimension().equals(current.getDimension())
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
        if (server != null) {
            endJobOf(server, current);
        }
        if (job == current) {
            job = null;
        }
        relayed.clear();
        returning = false;
        away = false;
        removeProgressBar();
        getNavigation().stop();
    }

    public static void endJobOf(MinecraftServer server, BuildJob current) {
        for (BuilderEntity member : Crew.loaded(server, current)) {
            member.leaveWork();
        }
        JourneymanRegistry.get(server).removeJob(current.getId());
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
        if (!current.getDimension().equals(level().dimension())) {
            if (!away) {
                away = true;
                getNavigation().stop();
                notifyOwner("I'm in " + level().dimension().location() + " now, away from the site of " + current.getFile() + " in "
                        + current.getDimension().location() + ", so my crew waits until I'm back there. Bring me back, or cancel the job with "
                        + "/journeymen foreman cancel " + getJourneymanName() + ".");
            }
            return;
        }
        if (away) {
            away = false;
            notifyOwner("I'm back at the site of " + current.getFile() + ", so my crew carries on.");
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
                    String shapeChange = loadingJob.shapeChange(schematic);
                    if (shapeChange != null) {
                        notifyOwner("I stopped working on " + loadingJob.getFile() + " because the file changed: " + shapeChange
                                + ". I've let the site go; give me the job again to build the new version.");
                        endJob(loadingJob);
                        return;
                    }
                    boolean changed = loadingJob.contentChanged(schematic);
                    loadingJob.setLoaded(schematic, null);
                    if (changed) {
                        notifyOwner(loadingJob.getFile() + " changed since I took the job, so my crew builds the new version from what is there now.");
                    }
                }, server);
    }

    private void keepPost(BuildJob current) {
        BoundingBox area = current.getArea();
        if (isInJobArea(blockPosition())) {
            returning = false;
            BlockPos target = postFor(current);
            if (target != null && (tickCount % 20 == 0 || getNavigation().isDone())) {
                walkToStand(target);
            }
            return;
        }
        post = null;
        double distance = PreCheck.horizontalDistance(blockPosition(), area);
        if (distance > POST_DISTANCE && !returning) {
            returning = true;
            returnBest = Double.MAX_VALUE;
            returnStalledTicks = 0;
        }
        if (!returning) {
            return;
        }
        if (distance <= POST_RETURN_DISTANCE) {
            returning = false;
            getNavigation().stop();
            return;
        }
        if (tickCount < returnRetryAt) {
            return;
        }
        if (distance < returnBest - 0.5) {
            returnBest = distance;
            returnStalledTicks = 0;
        } else if (++returnStalledTicks > StuckRecovery.giveUpTicks(RETURN_GIVE_UP_TICKS)) {
            returnBest = Double.MAX_VALUE;
            returnStalledTicks = 0;
            returnRetryAt = tickCount + RETURN_RETRY_TICKS;
            getNavigation().stop();
            relay("I can't find a way back to the site of " + current.getFile() + ", so I'm staying here and will try again in a while. "
                    + "Clear me a way, or bring me closer with /journeymen foreman unstick " + getJourneymanName() + ".");
            return;
        }
        if (tickCount % 20 == 0 || getNavigation().isDone()) {
            double x = Math.max(area.minX(), Math.min(area.maxX(), getX()));
            double z = Math.max(area.minZ(), Math.min(area.maxZ(), getZ()));
            walkTowards(new Vec3(x + 0.5, getY(), z + 0.5));
        }
    }

    @Nullable
    private BlockPos postFor(BuildJob current) {
        if (post != null && !isInJobArea(post) && tickCount - postSearchedAt < POST_SEARCH_TICKS) {
            return post;
        }
        postSearchedAt = tickCount;
        BlockPos found = PostSearch.find(this, current.getArea(), POST_DISTANCE);
        if (found == null) {
            StuckRecovery.Goal goal = siteGoal(current, true);
            found = goal == null ? null : getStuckRecovery().findSafeSpot(goal);
        }
        if (Services.PLATFORM.isDevelopmentEnvironment() && found != null && !found.equals(post)) {
            Constants.LOG.info("[post] {} stands at {} outside the site of {}", qualifiedName(), found.toShortString(), current.getFile());
        }
        if (found == null && post == null) {
            relay("I can't find a spot outside the site of " + current.getFile() + " to stand on, so I'm standing in it and may be in my crew's way. "
                    + "Make me room next to the site, or move me out of it.");
        }
        post = found;
        return post;
    }

    @Nullable
    @Override
    protected StuckRecovery.Goal workGoal() {
        BuildJob current = getJob();
        if (current == null || !current.getDimension().equals(level().dimension())) {
            return null;
        }
        BlockPos target = isInJobArea(blockPosition()) ? post : null;
        if (target != null) {
            return new StuckRecovery.Goal(StuckRecovery.Kind.SPOT, target, target, Math.sqrt(distanceToSqr(Vec3.atBottomCenterOf(target))),
                    "a post outside the site of " + current.getFile());
        }
        return returning ? siteGoal(current, false) : null;
    }

    @Nullable
    @Override
    protected StuckRecovery.Goal unstickWorkGoal() {
        BuildJob current = getJob();
        return current == null || !current.getDimension().equals(level().dimension()) ? null : siteGoal(current, true);
    }

    @Override
    protected int workDone() {
        return 0;
    }

    @Override
    protected void afterEscape() {
        returning = false;
        post = null;
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
        if (!current.getDimension().equals(level().dimension())) {
            return new JobStatus(BuildJob.Phase.PAUSED, "paused", getJourneymanName() + " is in " + level().dimension().location()
                    + ", away from the site", 0, 0);
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
        ServerPlayer viewer = ProgressBars.viewer(this);
        BuildJob current = getJob();
        if (current == null || !isAlive() || !ProgressBars.inRange(this, viewer)) {
            removeProgressBar();
            return;
        }
        JobStatus status = jobStatus();
        Component title = Component.literal(getJourneymanName() + "'s crew · " + current.getFile() + " · " + status.text());
        BossEvent.BossBarColor color = status.phase() == BuildJob.Phase.PAUSED ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.GREEN;
        ProgressBars.show(this, viewer, title, color, status.fraction());
    }

    private void removeProgressBar() {
        ProgressBars.remove(getUUID());
    }

    @Override
    protected void tickPeriodic() {
        if (tickCount % 10 == 0) {
            updateProgressBar();
            ForemanScreenService.tick(this);
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide) {
            if (canBeCommandedBy(player) && player instanceof ServerPlayer serverPlayer) {
                boolean linked = LinkModeService.finish(serverPlayer, this);
                ForemanScreenService.open(serverPlayer, this, linked ? ForemanMenu.CHESTS_TAB : ForemanMenu.JOB_TAB);
            } else {
                player.displayClientMessage(Component.literal(getJourneymanName() + " works for someone else."), true);
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    protected void onGone(boolean died) {
        removeProgressBar();
        LinkModeService.foremanGone(this);
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
        if (!level().isClientSide) {
            removeProgressBar();
            LinkModeService.foremanGone(this);
        }
        super.remove(reason);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        ListTag chestList = new ListTag();
        chests.forEach(chest -> chestList.add(chest.save()));
        tag.put("Chests", chestList);
        tag.putBoolean("AwayFromSite", away);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        chests.clear();
        chestsRefreshedAt = Long.MIN_VALUE;
        away = tag.getBoolean("AwayFromSite");
        ListTag chestList = tag.getList("Chests", Tag.TAG_COMPOUND);
        for (int i = 0; i < chestList.size(); i++) {
            AssignedChest chest = AssignedChest.load(chestList.getCompound(i), level().dimension());
            if (chest != null) {
                chests.add(chest);
            }
        }
    }
}
