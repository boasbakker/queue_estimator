package com.queueestimator.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.queueestimator.QueueEstimatorMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration for the Queue Estimator mod.
 * Stores which curve fitting formulas are enabled.
 */
public class QueueEstimatorConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Paths.get("config", "queue_estimator.json");

    private static QueueEstimatorConfig instance;

    // Formula toggles - all enabled by default
    private boolean linearEnabled = true;
    private boolean quadraticEnabled = true;
    private boolean exponentialEnabled = true; // Current shifted exponential: A * e^(-Bt) - C
    private boolean powerLawEnabled = true; // Power law: A * t^(-B) + C
    private boolean logarithmicEnabled = true; // Logarithmic: A - B * ln(t + 1)

    // Display settings
    private boolean showAllResults = true; // Show all successful fits or just the best one
    private int minDataPoints = 5; // Minimum data points before attempting fit

    private QueueEstimatorConfig() {
    }

    public static QueueEstimatorConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static QueueEstimatorConfig load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                String json = Files.readString(CONFIG_PATH);
                QueueEstimatorConfig config = GSON.fromJson(json, QueueEstimatorConfig.class);
                if (config != null) {
                    QueueEstimatorMod.LOGGER.info("Loaded config from {}", CONFIG_PATH);
                    return config;
                }
            }
        } catch (IOException e) {
            QueueEstimatorMod.LOGGER.error("Failed to load config", e);
        }

        QueueEstimatorMod.LOGGER.info("Using default config");
        return new QueueEstimatorConfig();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            String json = GSON.toJson(this);
            Files.writeString(CONFIG_PATH, json);
            QueueEstimatorMod.LOGGER.info("Saved config to {}", CONFIG_PATH);
        } catch (IOException e) {
            QueueEstimatorMod.LOGGER.error("Failed to save config", e);
        }
    }

    // Getters and setters

    public boolean isLinearEnabled() {
        return linearEnabled;
    }

    public void setLinearEnabled(boolean enabled) {
        this.linearEnabled = enabled;
    }

    public boolean isQuadraticEnabled() {
        return quadraticEnabled;
    }

    public void setQuadraticEnabled(boolean enabled) {
        this.quadraticEnabled = enabled;
    }

    public boolean isExponentialEnabled() {
        return exponentialEnabled;
    }

    public void setExponentialEnabled(boolean enabled) {
        this.exponentialEnabled = enabled;
    }

    public boolean isPowerLawEnabled() {
        return powerLawEnabled;
    }

    public void setPowerLawEnabled(boolean enabled) {
        this.powerLawEnabled = enabled;
    }

    public boolean isLogarithmicEnabled() {
        return logarithmicEnabled;
    }

    public void setLogarithmicEnabled(boolean enabled) {
        this.logarithmicEnabled = enabled;
    }

    public boolean isShowAllResults() {
        return showAllResults;
    }

    public void setShowAllResults(boolean showAll) {
        this.showAllResults = showAll;
    }

    public int getMinDataPoints() {
        return minDataPoints;
    }

    public void setMinDataPoints(int minPoints) {
        this.minDataPoints = Math.max(3, Math.min(20, minPoints));
    }

    /**
     * Check if at least one formula is enabled
     */
    public boolean hasAnyFormulaEnabled() {
        return linearEnabled || quadraticEnabled || exponentialEnabled ||
                powerLawEnabled || logarithmicEnabled;
    }
}
