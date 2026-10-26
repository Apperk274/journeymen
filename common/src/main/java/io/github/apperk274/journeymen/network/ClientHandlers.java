package io.github.apperk274.journeymen.network;

import io.github.apperk274.journeymen.client.ClientState;
import io.github.apperk274.journeymen.client.ConfirmScreen;
import io.github.apperk274.journeymen.client.HireScreen;
import io.github.apperk274.journeymen.client.PlacementMode;
import net.minecraft.client.Minecraft;

public final class ClientHandlers {

    private ClientHandlers() {
    }

    static void openHireScreen(OpenHireScreenPayload payload) {
        Minecraft.getInstance().setScreen(new HireScreen(payload));
    }

    static void builderState(ScreenPayloads.BuilderState payload) {
        ClientState.BUILDERS.put(payload.builderId(), payload);
    }

    static void foremanState(ScreenPayloads.ForemanState payload) {
        ClientState.FOREMEN.put(payload.foremanId(), payload);
    }

    static void schematicList(ScreenPayloads.SchematicList payload) {
        ClientState.schematics = payload.files();
    }

    static void schematicInfo(ScreenPayloads.SchematicInfo payload) {
        ClientState.schematicInfo(payload);
    }

    static void schematicBlocks(ScreenPayloads.SchematicBlocks payload) {
        ClientState.schematicBlocks(payload);
    }

    static void checkResult(ScreenPayloads.CheckResult payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (payload.started()) {
            PlacementMode.stop();
            if (minecraft.screen instanceof ConfirmScreen) {
                minecraft.setScreen(null);
            }
            return;
        }
        if (minecraft.screen instanceof ConfirmScreen confirm && confirm.matches(payload)) {
            confirm.update(payload);
        } else {
            minecraft.setScreen(new ConfirmScreen(payload));
        }
    }

    static void linkMode(ScreenPayloads.LinkMode payload) {
        ClientState.link = payload.active() ? payload : null;
    }
}
