package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.network.ScreenPayloads;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ClientState {

    public static final Map<Integer, ScreenPayloads.BuilderState> BUILDERS = new HashMap<>();
    public static final Map<Integer, ScreenPayloads.ForemanState> FOREMEN = new HashMap<>();
    public static List<String> schematics = List.of();
    @Nullable
    public static ScreenPayloads.SchematicInfo info;
    @Nullable
    public static ScreenPayloads.LinkMode link;
    @Nullable
    private static byte[][] previewParts;
    private static int previewReceived;

    private ClientState() {
    }

    public static void schematicInfo(ScreenPayloads.SchematicInfo payload) {
        info = payload;
        previewParts = payload.complete() ? new byte[payload.parts()][] : null;
        previewReceived = 0;
    }

    public static void schematicBlocks(ScreenPayloads.SchematicBlocks payload) {
        byte[][] parts = previewParts;
        if (info != null && parts != null && payload.file().equals(info.file()) && payload.part() >= 0 && payload.part() < parts.length
                && parts[payload.part()] == null) {
            parts[payload.part()] = payload.data();
            previewReceived++;
        }
    }

    @Nullable
    public static List<byte[]> previewData(ScreenPayloads.SchematicInfo schematic) {
        byte[][] parts = previewParts;
        return schematic == info && parts != null && previewReceived == parts.length ? List.of(parts) : null;
    }

    public static void reset() {
        BUILDERS.clear();
        FOREMEN.clear();
        schematics = List.of();
        info = null;
        link = null;
        previewParts = null;
        previewReceived = 0;
        PlacementMode.stop();
    }
}
