package io.github.apperk274.journeymen.network;

import io.github.apperk274.journeymen.entity.RecruiterEntity;
import io.github.apperk274.journeymen.hire.HireService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public final class ServerHandlers {

    private ServerHandlers() {
    }

    static void hireBuilder(HireBuilderPayload payload, ServerPlayer player) {
        Entity entity = player.level().getEntity(payload.recruiterId());
        if (!(entity instanceof RecruiterEntity recruiter)) {
            player.sendSystemMessage(Component.literal("That recruiter is gone."));
            return;
        }
        HireService.hire(player, recruiter, payload.name()).forEach(player::sendSystemMessage);
    }
}
