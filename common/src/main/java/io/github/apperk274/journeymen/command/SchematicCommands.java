package io.github.apperk274.journeymen.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.build.BlockRequirements;
import io.github.apperk274.journeymen.schematic.MaterialList;
import io.github.apperk274.journeymen.schematic.Schematic;
import io.github.apperk274.journeymen.schematic.SchematicFiles;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class SchematicCommands {

    public static final SuggestionProvider<CommandSourceStack> SCHEMATIC_FILES = (context, builder) -> {
        Stream<String> names;
        try {
            names = SchematicFiles.list().stream().map(StringArgumentType::escapeIfRequired);
        } catch (IOException e) {
            names = Stream.empty();
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("schematic")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("list").executes(SchematicCommands::list))
                .then(Commands.literal("materials")
                        .then(Commands.argument("file", StringArgumentType.string())
                                .suggests(SCHEMATIC_FILES)
                                .executes(SchematicCommands::materials)));
    }

    public static void loadAsync(CommandSourceStack source, String name, Consumer<Schematic> onLoaded) {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return SchematicFiles.load(name);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, Util.backgroundExecutor())
                .whenCompleteAsync((schematic, error) -> {
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        if (!(cause instanceof IOException)) {
                            Constants.LOG.error("Failed to load schematic {}", name, cause);
                        }
                        source.sendFailure(Component.literal("Could not load " + name + ": " + cause.getMessage()));
                        return;
                    }
                    onLoaded.accept(schematic);
                }, source.getServer());
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> names;
        try {
            names = SchematicFiles.list();
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not read " + SchematicFiles.directory() + ": " + e.getMessage()));
            return 0;
        }
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No schematics yet. Put .schem or .nbt files in " + SchematicFiles.directory()), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(names.size() + " schematic(s) in " + SchematicFiles.directory() + ":"), false);
        for (String name : names) {
            String command = "/" + Constants.MOD_ID + " schematic materials " + StringArgumentType.escapeIfRequired(name);
            source.sendSuccess(() -> Component.literal("  " + name).withStyle(style -> style
                    .withColor(ChatFormatting.YELLOW)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to show materials")))), false);
        }
        return names.size();
    }

    private static int materials(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "file");
        loadAsync(source, name, schematic -> sendMaterials(source, name, schematic, MaterialList.of(schematic, source.registryAccess())));
        return 1;
    }

    private static void sendMaterials(CommandSourceStack source, String name, Schematic schematic, MaterialList materials) {
        Vec3i size = schematic.getSize();
        source.sendSuccess(() -> Component.literal(name).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(": " + size.getX() + "×" + size.getY() + "×" + size.getZ()
                        + ", " + materials.getBlockCount() + " blocks").withStyle(ChatFormatting.GRAY)), false);
        if (materials.getItems().isEmpty()) {
            source.sendSuccess(() -> Component.literal("Nothing to place."), false);
        }
        for (Map.Entry<ItemStack, Long> entry : materials.getItems()) {
            source.sendSuccess(() -> itemLine(entry.getKey(), entry.getValue()), false);
        }
        for (Map.Entry<BlockRequirements.ToolUse, Integer> entry : materials.getToolUses().entrySet()) {
            source.sendSuccess(() -> Component.literal(String.format("%6d ", entry.getValue())).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("uses of " + entry.getKey().description()).withStyle(ChatFormatting.WHITE)), false);
        }
        for (Map.Entry<Block, Integer> entry : materials.getUnobtainable().entrySet()) {
            source.sendSuccess(() -> Component.literal("Can't be placed by hand: " + entry.getValue() + " ")
                    .append(entry.getKey().getName())
                    .withStyle(ChatFormatting.RED), false);
        }
        if (!schematic.getUnknownBlocks().isEmpty()) {
            source.sendSuccess(() -> Component.literal("Skipped blocks this game doesn't have (missing mod?): "
                    + String.join(", ", schematic.getUnknownBlocks().stream().sorted().toList()))
                    .withStyle(ChatFormatting.RED), false);
        }
    }

    public static MutableComponent itemLine(ItemStack stack, long count) {
        MutableComponent line = Component.literal(String.format("%6d ", count)).withStyle(ChatFormatting.YELLOW)
                .append(stack.getHoverName().copy().withStyle(ChatFormatting.WHITE));
        if (!stack.getComponentsPatch().isEmpty()) {
            line.append(Component.literal(" (with data)").withStyle(ChatFormatting.GRAY));
        }
        int stackSize = stack.getMaxStackSize();
        if (stackSize > 1 && count > stackSize) {
            long stacks = count / stackSize;
            long rest = count % stackSize;
            line.append(Component.literal(" (" + stacks + "×" + stackSize + (rest > 0 ? " + " + rest : "") + ")")
                    .withStyle(ChatFormatting.GRAY));
        }
        return line;
    }
}
