package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ClientState {

    private static final long INFO_TIMEOUT_MILLIS = 5000;

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
    @Nullable
    private static String wantedInfo;
    @Nullable
    private static String sentInfo;
    private static int infoForemanId;
    private static long infoSentAt = -1;

    private ClientState() {
    }

    public static void requestInfo(int foremanId, String file) {
        info = null;
        previewParts = null;
        wantedInfo = file;
        infoForemanId = foremanId;
        if (infoSentAt < 0 || Util.getMillis() - infoSentAt >= INFO_TIMEOUT_MILLIS) {
            sendInfoRequest();
        }
    }

    public static void tickInfo() {
        if (wantedInfo != null && !wantedInfo.equals(sentInfo) && infoSentAt >= 0 && Util.getMillis() - infoSentAt >= INFO_TIMEOUT_MILLIS) {
            sendInfoRequest();
        }
    }

    private static void sendInfoRequest() {
        sentInfo = wantedInfo;
        infoSentAt = Util.getMillis();
        Services.NETWORK.sendToServer(new ScreenPayloads.Action(infoForemanId, "schematic_info", wantedInfo, BlockPos.ZERO));
    }

    public static void schematicInfo(ScreenPayloads.SchematicInfo payload) {
        infoSentAt = -1;
        if (wantedInfo != null && !wantedInfo.equals(payload.file())) {
            sendInfoRequest();
            return;
        }
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
        wantedInfo = null;
        sentInfo = null;
        infoSentAt = -1;
        link = null;
        previewParts = null;
        previewReceived = 0;
        PlacementMode.stop();
    }
}
