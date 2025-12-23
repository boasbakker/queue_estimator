package com.queueestimator;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueueEstimatorMod implements ClientModInitializer {
    public static final String MOD_ID = "queue_estimator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Queue Estimator mod initialized!");
    }
}
