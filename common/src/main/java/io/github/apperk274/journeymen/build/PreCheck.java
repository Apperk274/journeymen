package io.github.apperk274.journeymen.build;

import com.mojang.authlib.GameProfile;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.BuilderRegistry;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PreCheck {

    private static final int MAX_LISTED_POSITIONS = 3;

    private final List<Component> problems = new ArrayList<>();
    private final List<Component> warnings = new ArrayList<>();
    private final List<Component> hints = new ArrayList<>();
    private int unplaceableCount;
    private long missingCount;
    private final BoundingBox area;
    @Nullable
    private BuildPlan plan;
    @Nullable
    private String stockNote;

    private PreCheck(BoundingBox area) {
        this.area = area;
    }

    public static PreCheck run(BuilderEntity builder, Schematic schematic, PlacementTransform transform, BuildOptions options) {
        ServerLevel level = (ServerLevel) builder.level();
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
        UUID owner = builder.getOwner();
        if (owner == null) {
            check.problem(builder.getBuilderName() + " has no owner, so it can't take jobs.");
            return check;
        }
        double distance = horizontalDistance(builder.blockPosition(), area);
        if (distance > JourneymenConfig.MAX_SITE_DISTANCE.get()) {
            check.problem("The site is " + Math.round(distance) + " blocks away from " + builder.getBuilderName() + "; the limit is "
                    + JourneymenConfig.MAX_SITE_DISTANCE.get() + ".");
        }
        BuilderRegistry.get(level.getServer()).findOverlappingJob(level.dimension(), area, builder.getUUID())
                .ifPresent(other -> check.problem("The site overlaps the job of " + other.qualifiedName() + "."));
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
                    + positions(plan.getFluidsInTheWay()) + ". " + builder.getBuilderName() + " will stop there and ask you to remove it.");
        }

        ServerPlayer actor = Services.PLATFORM.getFakePlayer(level, new GameProfile(owner, builder.getOwnerName()));
        Set<BlockPos> positions = new LinkedHashSet<>(plan.getToClear());
        plan.getToPlace().forEach(placement -> positions.add(placement.pos()));
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
            check.problem(builder.getOwnerName() + " isn't allowed to build at " + denied.size() + " position(s) of the site (spawn protection or a claim), e.g. "
                    + positions(denied) + ".");
        }

        int supplyChests = builder.countSupplyChests();
        check.stockNote = "Counted " + builder.getBuilderName() + "'s inventory" + (supplyChests == 0
                ? " (it has no supply chests)."
                : " and " + supplyChests + " supply chest" + (supplyChests == 1 ? "" : "s") + ".");
        List<ItemStack> stock = builder.collectStock(check.problems);
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
                long inDump = builder.countInDumpChests(entry.getKey());
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
        check.problems.addAll(ToolCheck.check(level, plan.getToClear(), toolUses, stock, options.allowBreakingEnchanted()));
        return check;
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
