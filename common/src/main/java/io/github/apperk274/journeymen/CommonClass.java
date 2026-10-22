package io.github.apperk274.journeymen;

import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.platform.Services;

public class CommonClass {

    public static void init() {
        Constants.LOG.info("{} initializing on {} ({})", Constants.MOD_NAME, Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());
        JourneymenConfig.load();
    }
}
