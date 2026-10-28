package io.github.apperk274.journeymen;

import io.github.apperk274.journeymen.platform.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Constants {

    public static final String MOD_ID = "journeymen";
    public static final String MOD_NAME = "Journeymen";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    public static void trace(String format, Object... arguments) {
        if (Services.PLATFORM.isDevelopmentEnvironment()) {
            LOG.info(format, arguments);
        } else {
            LOG.debug(format, arguments);
        }
    }
}
