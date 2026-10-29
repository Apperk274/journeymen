package io.github.apperk274.journeymen.menu;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RequestThrottle {

    public static final String BUSY = "Busy with your last request. Try again in a moment.";
    private static final int COOLDOWN_TICKS = 20;
    private static final int IN_FLIGHT_TICKS = 1200;
    private static final Map<UUID, Integer> IN_FLIGHT = new HashMap<>();
    private static final Map<UUID, Map<String, Integer>> STARTED = new HashMap<>();

    private RequestThrottle() {
    }

    public static boolean tryStart(ServerPlayer player, String action, boolean cooldown) {
        UUID id = player.getUUID();
        int now = player.server.getTickCount();
        Integer running = IN_FLIGHT.get(id);
        if (running != null && now - running < IN_FLIGHT_TICKS) {
            return false;
        }
        if (cooldown) {
            Map<String, Integer> started = STARTED.computeIfAbsent(id, key -> new HashMap<>());
            Integer last = started.get(action);
            if (last != null && now - last < COOLDOWN_TICKS) {
                return false;
            }
            started.put(action, now);
        }
        IN_FLIGHT.put(id, now);
        return true;
    }

    public static void finish(ServerPlayer player) {
        IN_FLIGHT.remove(player.getUUID());
    }

    public static void reset() {
        IN_FLIGHT.clear();
        STARTED.clear();
    }
}
