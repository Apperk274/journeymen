package io.github.apperk274.journeymen.network;

import io.github.apperk274.journeymen.client.HireScreen;
import net.minecraft.client.Minecraft;

public final class ClientHandlers {

    private ClientHandlers() {
    }

    static void openHireScreen(OpenHireScreenPayload payload) {
        Minecraft.getInstance().setScreen(new HireScreen(payload));
    }
}
