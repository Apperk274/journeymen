package io.github.apperk274.journeymen.build;

import com.mojang.authlib.GameProfile;
import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import io.github.apperk274.journeymen.platform.Services;
import io.github.apperk274.journeymen.schematic.Schematic;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PreCheck {

    private static final int MAX_LISTED_POSITIONS = 3;

    private final List<Component> problems = new ArrayList<>();
    private final List<Component> warnings = new ArrayList<>();
    private final List<Component> hints = new ArrayList<>();
    private int unplaceableCount;
    private long missingCount;
    private boolean missingTools;
    private final BoundingBox area;
    @Nullable
    private BuildPlan plan;
    @Nullable
    private String stockNote;

    private PreCheck(BoundingBox area) {
        this.area = area;
    }

    public static PreCheck run(ForemanEntity foreman, Schematic schematic, PlacementTransform transform, BuildOptions options) {
        ServerLevel level = (ServerLevel) foreman.level();
        Vec3i size = schematic.getSize();
        PreCheck check = new PreCheck(transform.area(size));
        BoundingBox area = check.area;

        long volume = (long) size.getX() * size.getY() * size.getZ();
        if (volume > JourneymenConfig.MAX_JOB_VOLUME.get()) {
            check.problem("The schematic is too large (" + volume + " blocks; the limit is " + JourneymenConfig.MAX_JOB_VOLUME.get() + ").");
            return check;
        }
        if (area.minY() < level.getMinBuildHeight() || area.maxY() >= level.getMaxBuildHeight()) {
            check.problem("The build would reach outside the world's height limits (" + level.getMinBuildHeight() + " to "
                    + (level.getMaxBuildHeight() - 1) + ").");
            return check;
        }
        if (foreman.getOwner() == null) {
            check.problem(foreman.getJourneymanName() + " has no owner, so he can't take jobs.");
            return check;
        }
        double distance = horizontalDistance(foreman.blockPosition(), area);
        if (distance > JourneymenConfig.MAX_SITE_DISTANCE.get()) {
            check.problem("The site is " + Math.round(distance) + " blocks away from " + foreman.getJourneymanName() + "; the limit is "
                    + JourneymenConfig.MAX_SITE_DISTANCE.get() + ".");
        } else {
            check.checkPath(foreman, area);
        }
        List<BuilderEntity> crew = foreman.loadedCrewHere();
        List<BuilderEntity> working = crew.stream().filter(member -> ForemanEntity.canTakePart(member, level.dimension(), area)).toList();
        if (foreman.crewEntries().isEmpty()) {
            check.problem(foreman.getJourneymanName() + " has no builders. Hire one with /journeymen foreman hire " + foreman.getJourneymanName() + ".");
        } else if (working.isEmpty()) {
            check.problem("None of " + foreman.getJourneymanName() + "'s builders can take part: they aren't loaded, are in another dimension or are more than "
                    + JourneymenConfig.MAX_SITE_DISTANCE.get() + " blocks from the site.");
        }
        BuildJob ownJob = foreman.getJob();
        JourneymanRegistry.get(level.getServer()).findOverlappingJob(level.dimension(), area, ownJob == null ? null : ownJob.getId())
                .ifPresent(other -> check.problem("The site overlaps the job of " + other + "."));
        if (!BuildPlan.isLoaded(level, area)) {
            check.problem("Part of the site isn't loaded. Go closer to it and try again.");
            return check;
        }

        BuildPlan plan = BuildPlan.create(level, schematic, transform, options);
        check.plan = plan;
        if (!schematic.getUnknownBlocks().isEmpty()) {
            check.warning("Skipping blocks this game doesn't have (missing mod?): " + String.join(", ", schematic.getUnknownBlocks().stream().sorted().toList()));
        }

        BuildPlan.Requirements requirements = plan.requirements(level.registryAccess());
        ItemCounter needed = requirements.items();
        Map<BlockRequirements.ToolUse, Integer> toolUses = requirements.toolUses();
        requirements.unobtainable().forEach((block, count) -> check.problem(Component.literal("Can't be placed by hand: " + count + " ").append(block.getName())));
        plan.getUnplaceable().forEach((block, count) -> check.warnings.add(Component.literal("Skipping " + count + " ").append(block.getName())
                .append(Component.literal(", which can't be placed by hand."))));
        check.unplaceableCount = requirements.unobtainable().values().stream().mapToInt(Integer::intValue).sum()
                + plan.getUnplaceable().values().stream().mapToInt(Integer::intValue).sum();
        if (!requirements.unobtainable().isEmpty()) {
            check.hints.add(Component.literal("Add the " + BuildOptions.SKIP_UNPLACEABLE + " option to build without the blocks that can't be placed by hand."));
        }

        List<BlockPos> unbreakable = new ArrayList<>();
        for (BlockPos pos : plan.getToClear()) {
            if (level.getBlockState(pos).getDestroySpeed(level, pos) < 0.0F) {
                unbreakable.add(pos);
            }
        }
        if (!unbreakable.isEmpty()) {
            BlockState example = level.getBlockState(unbreakable.get(0));
            check.problem(Component.literal(unbreakable.size() + " unbreakable block(s) in the way, e.g. ").append(example.getBlock().getName())
                    .append(Component.literal(" at " + positions(unbreakable))));
        }
        if (!plan.getFluidsInTheWay().isEmpty()) {
            check.warning("Water or lava is in the way at " + plan.getFluidsInTheWay().size() + " position(s), e.g. "
                    + positions(plan.getFluidsInTheWay()) + ". The crew will stop there and " + foreman.getJourneymanName() + " will ask you to remove it.");
        }

        Set<BlockPos> positions = new LinkedHashSet<>(plan.getToClear());
        plan.getToPlace().forEach(placement -> positions.add(placement.pos()));
        ServerPlayer actor = Services.PLATFORM.getFakePlayer(level, new GameProfile(foreman.getOwner(), foreman.getOwnerName()));
        List<BlockPos> denied = new ArrayList<>();
        ItemStack held = actor.getMainHandItem();
        actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        try {
            for (BlockPos pos : positions) {
                if (!level.mayInteract(actor, pos) || !Services.PLATFORM.mayStartBreaking(actor, pos)) {
                    denied.add(pos);
                }
            }
        } finally {
            actor.setItemInHand(InteractionHand.MAIN_HAND, held);
        }
        if (!denied.isEmpty()) {
            check.problem(foreman.getOwnerName() + " isn't allowed to build at " + denied.size() + " position(s) of the site (spawn protection or a claim), e.g. "
                    + positions(denied) + ".");
        }

        int supplyChests = Crew.countSupplyChests(foreman);
        String chests = supplyChests == 0 ? "no supply chests" : supplyChests + " supply chest" + (supplyChests == 1 ? "" : "s");
        String counted = crew.isEmpty() ? "no builder's inventory (none is loaded here)"
                : crew.size() == 1 ? crew.get(0).getJourneymanName() + "'s inventory" : "the inventories of " + Crew.names(crew);
        check.stockNote = "Counted " + counted + (crew.size() > 1 ? ", and " : " and ") + foreman.getJourneymanName() + "'s " + chests + ".";
        List<ItemStack> stock = Crew.stock(foreman, crew, check.problems);
        ItemCounter available = new ItemCounter();
        for (ItemStack stack : stock) {
            available.add(stack, stack.getCount());
        }
        for (Map.Entry<ItemStack, Long> entry : needed.sorted()) {
            long missing = entry.getValue() - available.count(entry.getKey());
            if (missing > 0) {
                MutableComponent line = Component.literal("Missing " + missing + " ").append(ItemMatch.name(entry.getKey()));
                if (ItemMatch.hasVanillaData(entry.getKey())) {
                    line.append(Component.literal(" (with specific data, e.g. patterns or contents)"));
                }
                long inDump = Crew.countInDumpChests(foreman, entry.getKey());
                if (inDump > 0) {
                    line.append(Component.literal(". " + inDump + " are in a dump chest, which doesn't count as stock"));
                }
                check.missingCount += missing;
                if (options.skipMissing()) {
                    check.warnings.add(line.append(Component.literal("; those blocks will be skipped")));
                } else {
                    check.problems.add(line);
                }
            }
        }
        if (check.missingCount > 0 && !options.skipMissing()) {
            check.hints.add(Component.literal("Add the " + BuildOptions.SKIP_MISSING + " option to build without the blocks whose materials are missing."));
        }
        ToolCheck.Shortfalls tools = ToolCheck.shortfalls(level, plan.getToClear(), toolUses, stock, options.allowBreakingEnchanted());
        check.problems.addAll(tools.problems());
        check.missingTools = tools.suppliable();
        return check;
    }

    private void checkPath(ForemanEntity foreman, BoundingBox area) {
        SitePath.Result path = SitePath.find(foreman, area, ForemanEntity.POST_DISTANCE);
        BlockPos pos = foreman.blockPosition();
        if (Services.PLATFORM.isDevelopmentEnvironment()) {
            Constants.LOG.info("[path] {} to the site: {} after {} nodes in {} ms", foreman.qualifiedName(),
                    path.outcome().name().toLowerCase(Locale.ROOT), path.visited(), String.format(Locale.ROOT, "%.1f", path.nanos() / 1.0E6));
        }
        String from = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        switch (path.outcome()) {
            case NONE -> problem(foreman.getJourneymanName() + " can't walk to the site: there is no way from where he stands (" + from
                    + ") to within " + (int) ForemanEntity.POST_DISTANCE + " blocks of it.");
            case UNKNOWN -> warning(foreman.getJourneymanName() + " found no way yet from where he stands (" + from + ") to within "
                    + (int) ForemanEntity.POST_DISTANCE + " blocks of the site, but didn't search everywhere. He'll try to walk there.");
            default -> {
            }
        }
    }

    public boolean passed() {
        return problems.isEmpty() && plan != null;
    }

    @Nullable
    public BuildPlan getPlan() {
        return plan;
    }

    public int getUnplaceableCount() {
        return unplaceableCount;
    }

    public long getMissingCount() {
        return missingCount;
    }

    public boolean isMissingTools() {
        return missingTools;
    }

    public BoundingBox getArea() {
        return area;
    }

    public List<Component> lines() {
        List<Component> lines = new ArrayList<>();
        if (plan != null) {
            lines.add(Component.literal(plan.getToClear().size() + " to clear, " + plan.getToPlace().size() + " to place, "
                    + (plan.getToFix().size() + plan.getToWaterlog().size()) + " to adjust, " + plan.getDoneCount() + " already done.")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (stockNote != null) {
            lines.add(Component.literal(stockNote).withStyle(ChatFormatting.GRAY));
        }
        for (Component problem : problems) {
            lines.add(Component.literal("✗ ").append(problem).withStyle(ChatFormatting.RED));
        }
        for (Component warning : warnings) {
            lines.add(Component.literal("! ").append(warning).withStyle(ChatFormatting.GOLD));
        }
        for (Component hint : hints) {
            lines.add(hint.copy().withStyle(ChatFormatting.GRAY));
        }
        if (passed()) {
            lines.add(Component.literal("✓ Everything is ready.").withStyle(ChatFormatting.GREEN));
        }
        return lines;
    }

    private void problem(String message) {
        problems.add(Component.literal(message));
    }

    private void problem(Component message) {
        problems.add(message);
    }

    private void warning(String message) {
        warnings.add(Component.literal(message));
    }

    private static String positions(List<BlockPos> positions) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_LISTED_POSITIONS, positions.size()); i++) {
            BlockPos pos = positions.get(i);
            parts.add(pos.getX() + " " + pos.getY() + " " + pos.getZ());
        }
        return String.join(", ", parts) + (positions.size() > MAX_LISTED_POSITIONS ? ", ..." : "");
    }

    public static double horizontalDistance(BlockPos pos, BoundingBox area) {
        int dx = Math.max(0, Math.max(area.minX() - pos.getX(), pos.getX() - area.maxX()));
        int dz = Math.max(0, Math.max(area.minZ() - pos.getZ(), pos.getZ() - area.maxZ()));
        return Math.sqrt((double) dx * dx + (double) dz * dz);
    }
}
