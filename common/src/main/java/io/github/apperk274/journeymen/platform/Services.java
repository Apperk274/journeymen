package io.github.apperk274.journeymen.platform;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.platform.services.INetworkHelper;
import io.github.apperk274.journeymen.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

public class Services {

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);
    public static final INetworkHelper NETWORK = load(INetworkHelper.class);

    public static <T> T load(Class<T> clazz) {
        final T loadedService = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}
