package io.github.apperk274.journeymen.menu;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.build.BuildJob;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.entity.JourneymanEntity;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public final class ScreenActions {

    private ScreenActions() {
    }

    public static void handle(ScreenPayloads.Action action, ServerPlayer player) {
        handle(action, player, player::sendSystemMessage);
    }

    public static void handle(ScreenPayloads.Action action, ServerPlayer player, Consumer<Component> reply) {
        Entity entity = player.level().getEntity(action.journeymanId());
        if (!(entity instanceof JourneymanEntity journeyman) || !journeyman.isAlive()) {
            tell(reply, "That journeyman is gone.", false);
            return;
        }
        if (!journeyman.canBeCommandedBy(player)) {
            tell(reply, journeyman.getJourneymanName() + " works for someone else.", false);
            return;
        }
        String name = journeyman.getJourneymanName();
        switch (action.action()) {
            case "follow", "stay" -> {
                boolean follow = action.action().equals("follow");
                String busy = busy(journeyman);
                if (busy != null) {
                    tell(reply, busy, false);
                } else {
                    journeyman.setFollowing(follow);
                    tell(reply, name + (follow ? " follows you now." : " stays here now."), true);
                }
            }
            case "rename" -> {
                String problem = journeyman.rename(action.text().strip());
                tell(reply, problem != null ? problem : name + " is now called " + journeyman.getJourneymanName() + ".", problem == null);
            }
            case "dismiss" -> {
                player.closeContainer();
                journeyman.kill();
                tell(reply, "You dismissed " + name + ".", true);
                return;
            }
            case "schematic_list" -> ForemanScreenService.sendSchematicList(player);
            case "schematic_info" -> ForemanScreenService.sendSchematicInfo(player, action.text(), reply);
            default -> {
                if (journeyman instanceof ForemanEntity foreman) {
                    ForemanScreenService.action(player, foreman, action, reply);
                } else {
                    String foreman = JourneymanRegistry.get(player.server).get(((BuilderEntity) journeyman).getForemanId())
                            .map(JourneymanRegistry.Entry::name).orElse(null);
                    tell(reply, foreman == null ? name + " has no foreman. Adopt it from a foreman's Crew tab."
                            : "Builders take no orders about jobs, chests or crews. Ask " + foreman + ".", false);
                }
            }
        }
        if (journeyman instanceof ForemanEntity foreman) {
            ForemanScreenService.sendState(player, foreman);
        } else if (journeyman instanceof BuilderEntity builder) {
            BuilderScreenService.sendState(player, builder);
        }
    }

    @Nullable
    private static String busy(JourneymanEntity journeyman) {
        BuildJob job = journeyman.getJob();
        if (job == null) {
            return null;
        }
        ForemanEntity foreman = journeyman instanceof BuilderEntity builder ? builder.foreman() : null;
        return journeyman.getJourneymanName() + " is busy with " + job.getFile()
                + (foreman != null ? " for " + foreman.getJourneymanName() + ". Cancel his job first." : ". Cancel the job first.");
    }

    static void tell(Consumer<Component> reply, String message, boolean good) {
        reply.accept(Component.literal(message).withStyle(good ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    static void logOpen(ServerPlayer player, JourneymanEntity journeyman, String tab) {
        if (Services.PLATFORM.isDevelopmentEnvironment()) {
            Constants.LOG.info("[screen] {} opens {}'s screen on the {} tab", player.getGameProfile().getName(), journeyman.getJourneymanName(), tab);
        }
    }
}
