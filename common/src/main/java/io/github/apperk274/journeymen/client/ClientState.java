package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.network.ScreenPayloads;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ClientState {

    public static final Map<Integer, ScreenPayloads.BuilderState> STATES = new HashMap<>();
    public static List<String> schematics = List.of();
    @Nullable
    public static ScreenPayloads.SchematicInfo info;
    @Nullable
    public static ScreenPayloads.LinkMode link;

    private ClientState() {
    }

    public static void reset() {
        STATES.clear();
        schematics = List.of();
        info = null;
        link = null;
        PlacementMode.stop();
    }
}
