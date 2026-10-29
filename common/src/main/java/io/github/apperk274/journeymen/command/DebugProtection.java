package io.github.apperk274.journeymen.command;

import io.github.apperk274.journeymen.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

public final class DebugProtection {

    public static final String ANYONE = "-";

    private record Area(BoundingBox box, String allowed) {
    }

    private static final List<Area> AREAS = new ArrayList<>();

    private DebugProtection() {
    }

    static void add(BoundingBox box, String allowed) {
        AREAS.add(new Area(box, allowed));
    }

    static int clear() {
        int count = AREAS.size();
        AREAS.clear();
        return count;
    }

    public static boolean denies(String event, Player player, BlockPos pos) {
        if (AREAS.isEmpty() || player.level().isClientSide()) {
            return false;
        }
        String name = player.getGameProfile().getName();
        boolean seen = false;
        boolean denied = false;
        for (Area area : AREAS) {
            if (area.box().isInside(pos)) {
                seen = true;
                denied |= !area.allowed().equals(ANYONE) && !area.allowed().equals(name);
            }
        }
        if (seen) {
            Constants.LOG.info("[protect] {} by {} at {}: {}", event, name, pos.toShortString(), denied ? "denied" : "allowed");
        }
        return denied;
    }
}
