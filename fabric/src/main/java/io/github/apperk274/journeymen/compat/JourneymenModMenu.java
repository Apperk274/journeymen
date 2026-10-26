package io.github.apperk274.journeymen.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.apperk274.journeymen.client.ConfigScreen;

public class JourneymenModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<ConfigScreen> getModConfigScreenFactory() {
        return ConfigScreen::new;
    }
}
