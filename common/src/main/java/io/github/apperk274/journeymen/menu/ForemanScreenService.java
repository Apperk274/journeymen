package io.github.apperk274.journeymen.menu;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.build.BlockRequirements;
import io.github.apperk274.journeymen.build.BuildJob;
import io.github.apperk274.journeymen.build.BuildOptions;
import io.github.apperk274.journeymen.build.Crew;
import io.github.apperk274.journeymen.build.JobService;
import io.github.apperk274.journeymen.build.MaterialSupply;
import io.github.apperk274.journeymen.command.SchematicCommands;
import io.github.apperk274.journeymen.command.Status;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.entity.JourneymanNames;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import io.github.apperk274.journeymen.hire.HirePrice;
import io.github.apperk274.journeymen.hire.HireService;
import io.github.apperk274.journeymen.network.PreviewBlocks;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import io.github.apperk274.journeymen.schematic.MaterialList;
import io.github.apperk274.journeymen.schematic.Schematic;
import io.github.apperk274.journeymen.schematic.SchematicFiles;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public final class ForemanScreenService {

    private ForemanScreenService() {
    }

    public static void open(ServerPlayer player, ForemanEntity foreman, int tab) {
        ScreenActions.logOpen(player, foreman, ForemanMenu.tabName(tab));
        Services.MENUS.openForemanMenu(player, foreman, tab);
        sendState(player, foreman);
        sendSchematicList(player);
    }

    public static void tick(ForemanEntity foreman) {
        for (ServerPlayer player : foreman.getServer().getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof ForemanMenu menu && menu.getForeman() == foreman) {
                sendState(player, foreman);
            }
        }
    }

    public static void sendState(ServerPlayer player, ForemanEntity foreman) {
        BuildJob job = foreman.getJob();
        ForemanEntity.JobStatus status = job == null ? null : foreman.jobStatus();
        String where = job == null ? "" : "at " + job.getOrigin().toShortString() + ", rotation " + job.getRotation().getSerializedName()
                + ", options " + job.getOptions().describe();
        String detail = status != null && status.phase() == BuildJob.Phase.PAUSED && status.pauseReason() != null ? status.pauseReason() : "";
        JourneymanRegistry registry = JourneymanRegistry.get(player.server);
        List<ScreenPayloads.CrewEntry> crew = new ArrayList<>();
        for (JourneymanRegistry.Entry member : foreman.crewEntries()) {
            Status.CrewMember state = Status.crewMember(player.server, job, member);
            crew.add(new ScreenPayloads.CrewEntry(member.name(), state.doing(), state.available()));
        }
        crew.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        UUID owner = foreman.getOwner();
        List<String> orphans = owner == null ? List.of()
                : registry.orphansOf(owner).stream().map(JourneymanRegistry.Entry::name).sorted(String::compareToIgnoreCase).toList();
        Services.NETWORK.sendToPlayer(player, new ScreenPayloads.ForemanState(foreman.getId(), foreman.getJourneymanName(), foreman.getOwnerName(),
                foreman.isFollowing(), job != null, foreman.getHealth(), foreman.getMaxHealth(), job == null ? "" : job.getFile(), where,
                status == null ? Status.activity(foreman) : status.text(), detail, status == null ? 0 : status.done(), status == null ? 0 : status.total(),
                LinkModeService.chestEntries(foreman), crew, orphans, hiring(player, foreman)));
    }

    private static ScreenPayloads.Hiring hiring(ServerPlayer player, ForemanEntity foreman) {
        JourneymanRegistry registry = JourneymanRegistry.get(player.server);
        UUID owner = foreman.getOwner();
        int builders = owner == null ? 0 : registry.ownedBy(owner, JourneymanRegistry.Kind.BUILDER).size();
        int maxBuilders = JourneymenConfig.MAX_BUILDERS_PER_PLAYER.get();
        int maxCrew = JourneymenConfig.MAX_CREW_SIZE.get();
        String name = suggestedName(player, foreman);
        HirePrice cost = HireService.cost();
        if (cost == null) {
            return new ScreenPayloads.Hiring(ItemStack.EMPTY, 0, false, false, builders, maxBuilders, maxCrew, name,
                    "The hire price item in the Journeymen config doesn't exist.");
        }
        long price = HireService.builderPrice(builders);
        int have = cost.count(player.getInventory());
        boolean free = player.getAbilities().instabuild;
        String problem = "";
        if (!free && !HireService.payable(price)) {
            problem = "A builder would cost more than anyone can pay. Ask an admin to lower hire_cost_base or hire_cost_step.";
        } else if (foreman.isCrewFull()) {
            problem = "His crew is full (" + maxCrew + " builders).";
        } else if (maxBuilders > 0 && builders >= maxBuilders) {
            problem = (player.getUUID().equals(owner) ? "You have " : foreman.getOwnerName() + " has ") + builders
                    + " builders, the most you can hire.";
        }
        return new ScreenPayloads.Hiring(cost.display(player.getInventory(), HireService.shownPrice(price)), have,
                free || HireService.payable(price) && have >= price, free,
                builders, maxBuilders, maxCrew, name, problem);
    }

    private static String suggestedName(ServerPlayer player, ForemanEntity foreman) {
        UUID owner = foreman.getOwner();
        JourneymanRegistry registry = JourneymanRegistry.get(player.server);
        ForemanMenu menu = player.containerMenu instanceof ForemanMenu open && open.getForeman() == foreman ? open : null;
        String name = menu == null ? null : menu.getSuggestedName();
        if (owner == null || name != null && !registry.isNameTaken(owner, name, null)) {
            return name == null ? "" : name;
        }
        name = JourneymanNames.random(player.getRandom(), new HashSet<>(registry.namesOf(owner)));
        if (menu != null) {
            menu.setSuggestedName(name);
        }
        return name;
    }

    static void action(ServerPlayer player, ForemanEntity foreman, ScreenPayloads.Action action, Consumer<Component> reply) {
        String name = foreman.getJourneymanName();
        switch (action.action()) {
            case "notices" -> {
                if (!player.getUUID().equals(foreman.getOwner())) {
                    ScreenActions.tell(reply, "Only " + foreman.getOwnerName() + " can read " + name + "'s notices.", false);
                } else {
                    List<String> notices = JourneymanRegistry.get(player.server).takeNotices(player.getUUID());
                    if (notices.isEmpty()) {
                        ScreenActions.tell(reply, "Your journeymen have nothing new to tell you.", true);
                    }
                    notices.forEach(notice -> reply.accept(Component.literal(notice)));
                }
            }
            case "cancel_job" -> {
                BuildJob job = foreman.getJob();
                if (job == null) {
                    ScreenActions.tell(reply, name + " has no job.", false);
                } else {
                    foreman.cancelJob();
                    ScreenActions.tell(reply, name + "'s crew stopped working on " + job.getFile() + ".", true);
                }
            }
            case "remove_chest" -> {
                ResourceLocation dimension = action.text().isBlank() ? null : ResourceLocation.tryParse(action.text().strip());
                if (foreman.removeChest(dimension == null ? foreman.level().dimension() : ResourceKey.create(Registries.DIMENSION, dimension),
                        action.pos())) {
                    ScreenActions.tell(reply, name + " no longer uses the chest at " + action.pos().toShortString() + ".", true);
                } else {
                    ScreenActions.tell(reply, name + " has no chest at " + action.pos().toShortString() + ".", false);
                }
            }
            case "link_supply", "link_dump" -> {
                player.closeContainer();
                LinkModeService.start(player, foreman, action.action().equals("link_supply"));
            }
            case "check", "start" -> runJob(player, foreman, action, action.action().equals("start") ? JobAction.START : JobAction.CHECK, reply);
            case "supply_missing" -> {
                if (player.isCreative()) {
                    runJob(player, foreman, action, JobAction.SUPPLY_MISSING, reply);
                } else {
                    ScreenActions.tell(reply, "Only players in creative mode can supply missing items.", false);
                }
            }
            case "hire" -> {
                int before = foreman.crewEntries().size();
                HireService.hireBuilder(player, foreman, action.text().strip()).forEach(reply);
                if (foreman.crewEntries().size() > before && player.containerMenu instanceof ForemanMenu menu && menu.getForeman() == foreman) {
                    menu.setSuggestedName(null);
                }
            }
            case "adopt" -> {
                String builder = action.text().strip();
                JourneymanRegistry.Entry entry = foreman.getOwner() == null ? null
                        : JourneymanRegistry.get(player.server).find(foreman.getOwner(), builder).orElse(null);
                String problem = entry == null ? foreman.getOwnerName() + " has no builder named " + builder + "."
                        : HireService.adopt(player.server, foreman, entry);
                ScreenActions.tell(reply, problem != null ? problem : name + " adopted " + entry.name() + ".", problem == null);
            }
            default -> {
            }
        }
    }

    public static void sendSchematicList(ServerPlayer player) {
        List<String> files;
        try {
            files = SchematicFiles.list();
        } catch (IOException e) {
            files = List.of();
        }
        Services.NETWORK.sendToPlayer(player, new ScreenPayloads.SchematicList(files));
    }

    private enum JobAction {
        CHECK,
        START,
        SUPPLY_MISSING
    }

    private static void runJob(ServerPlayer player, ForemanEntity foreman, ScreenPayloads.Action action, JobAction jobAction,
                               Consumer<Component> reply) {
        String[] parts = action.text().split("\n", -1);
        if (parts.length < 3) {
            return;
        }
        String file = parts[0];
        Rotation rotation = Rotation.NONE;
        for (Rotation candidate : Rotation.values()) {
            if (candidate.getSerializedName().equals(parts[1])) {
                rotation = candidate;
            }
        }
        BuildOptions options;
        try {
            options = BuildOptions.parse(parts[2]);
        } catch (IllegalArgumentException e) {
            ScreenActions.tell(reply, e.getMessage(), false);
            return;
        }
        if (!RequestThrottle.tryStart(player, action.action(), true)) {
            ScreenActions.tell(reply, RequestThrottle.BUSY, false);
            return;
        }
        BlockPos origin = action.pos();
        Rotation finalRotation = rotation;
        Consumer<JobService.Result> done = result -> {
            RequestThrottle.finish(player);
            List<Component> lines = new ArrayList<>();
            MaterialSupply.Outcome supplied = result.supplied();
            if (supplied != null) {
                lines.add(supplied.summary());
                lines.addAll(supplied.notes());
                reply.accept(supplied.summary());
                supplied.notes().forEach(reply);
                Constants.LOG.info("[supply] {} for {}: {} The check {} now.", player.getName().getString(), foreman.getJourneymanName(),
                        supplied.summary().getString(), result.passed() ? "passes" : "still fails");
            }
            if (result.header() != null) {
                lines.add(result.header());
                if (Services.PLATFORM.isDevelopmentEnvironment()) {
                    Constants.LOG.info("[check] {} for {}: {} -> {}", player.getName().getString(), foreman.getJourneymanName(),
                            result.header().getString(), result.passed() ? "passes" : "fails");
                }
            }
            lines.addAll(result.lines());
            Services.NETWORK.sendToPlayer(player, new ScreenPayloads.CheckResult(foreman.getId(), file, origin, finalRotation.getSerializedName(),
                    options.describe().equals("none") ? "" : options.describe(), lines, result.passed(), result.started(),
                    result.error() == null ? "" : result.error(), result.unplaceable(), (int) Math.min(Integer.MAX_VALUE, result.missing()),
                    result.missingTools()));
            if (result.started()) {
                ScreenActions.tell(reply, foreman.getJourneymanName() + "'s crew is on the way to build " + file + ": " + Crew.list(result.crew()) + ".",
                        true);
            } else if (result.error() != null && jobAction != JobAction.CHECK) {
                ScreenActions.tell(reply, result.error(), false);
            }
        };
        if (jobAction == JobAction.SUPPLY_MISSING) {
            JobService.supplyMissing(player.server, foreman, file, origin, rotation, options, player, done);
        } else {
            JobService.run(player.server, foreman, file, origin, rotation, options, jobAction == JobAction.START, done);
        }
    }

    static void sendSchematicInfo(ServerPlayer player, String file, Consumer<Component> reply) {
        if (!RequestThrottle.tryStart(player, "schematic_info", false)) {
            ScreenActions.tell(reply, RequestThrottle.BUSY, false);
            return;
        }
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        Schematic schematic = SchematicFiles.load(file);
                        return new LoadedSchematic(schematic, PreviewBlocks.encode(schematic));
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, Util.backgroundExecutor())
                .whenCompleteAsync((loaded, error) -> {
                    RequestThrottle.finish(player);
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        ScreenActions.tell(reply, "Could not load " + file + ": " + cause.getMessage(), false);
                        return;
                    }
                    ScreenPayloads.SchematicInfo info = info(file, loaded, player);
                    if (Services.PLATFORM.isDevelopmentEnvironment()) {
                        logRoundTrip(player, info);
                    }
                    Services.NETWORK.sendToPlayer(player, info);
                    List<byte[]> parts = loaded.preview().parts();
                    for (int i = 0; i < parts.size(); i++) {
                        Services.NETWORK.sendToPlayer(player, new ScreenPayloads.SchematicBlocks(file, i, parts.get(i)));
                    }
                }, player.server);
    }

    private record LoadedSchematic(Schematic schematic, PreviewBlocks.Encoded preview) {
    }

    private static void logRoundTrip(ServerPlayer player, ScreenPayloads.SchematicInfo info) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            ScreenPayloads.SchematicInfo.CODEC.encode(buf, info);
            ScreenPayloads.SchematicInfo decoded = ScreenPayloads.SchematicInfo.CODEC.decode(buf);
            Constants.LOG.info("[schematic_info] {} {}: size {} {} {}, offset {} {} {}", player.getGameProfile().getName(), decoded.file(),
                    decoded.size().getX(), decoded.size().getY(), decoded.size().getZ(),
                    decoded.offset().getX(), decoded.offset().getY(), decoded.offset().getZ());
        } finally {
            buf.release();
        }
    }

    private static ScreenPayloads.SchematicInfo info(String file, LoadedSchematic loaded, ServerPlayer player) {
        Schematic schematic = loaded.schematic();
        MaterialList materials = MaterialList.of(schematic, player.registryAccess());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(schematic.getSize().getX() + "×" + schematic.getSize().getY() + "×" + schematic.getSize().getZ()
                + ", " + materials.getBlockCount() + " blocks").withStyle(ChatFormatting.GRAY));
        for (Map.Entry<ItemStack, Long> entry : materials.getItems()) {
            lines.add(SchematicCommands.itemLine(entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<BlockRequirements.ToolUse, Integer> entry : materials.getToolUses().entrySet()) {
            lines.add(Component.literal(entry.getValue() + " uses of " + entry.getKey().description()).withStyle(ChatFormatting.GRAY));
        }
        for (Map.Entry<Block, Integer> entry : materials.getUnobtainable().entrySet()) {
            lines.add(Component.literal("Can't be placed by hand: " + entry.getValue() + " ").append(entry.getKey().getName())
                    .withStyle(ChatFormatting.RED));
        }
        boolean complete = loaded.preview().complete();
        return new ScreenPayloads.SchematicInfo(file, new BlockPos(schematic.getSize()), new BlockPos(schematic.getPasteOffset()), lines,
                complete ? schematic.getPalette() : List.of(), loaded.preview().parts().size(), complete);
    }
}
