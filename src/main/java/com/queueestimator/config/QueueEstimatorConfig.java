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
    private boolean tangentEnabled = true; // Tangent: A * tan(B - k*t) - D
    private boolean hyperbolicEnabled = true; // Hyperbolic: A/(t+B) - C

    // Display settings
    private boolean showAllResults = true; // Show all successful fits or just the best one
    private int ignoreFirstMinutes = 10; // Ignore first X minutes of data (warmup period)

    // Linear fit windowing - 0 = use all data, otherwise only use last X hours of
    // data
    private int linearWindowHours = 0; // Default: 0 = use all data

    // Rate tracking interval - print average rate every X hours
    private double rateTrackingIntervalHours = 1.0; // Default: 1 hour

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

    public boolean isTangentEnabled() {
        return tangentEnabled;
    }

    public void setTangentEnabled(boolean enabled) {
        this.tangentEnabled = enabled;
    }

    public boolean isHyperbolicEnabled() {
        return hyperbolicEnabled;
    }

    public void setHyperbolicEnabled(boolean enabled) {
        this.hyperbolicEnabled = enabled;
    }

    public boolean isShowAllResults() {
        return showAllResults;
    }

    public void setShowAllResults(boolean showAll) {
        this.showAllResults = showAll;
    }

    public int getIgnoreFirstMinutes() {
        return ignoreFirstMinutes;
    }

    public void setIgnoreFirstMinutes(int minutes) {
        this.ignoreFirstMinutes = Math.max(0, Math.min(60, minutes)); // 0 to 60 minutes
    }

    public int getLinearWindowHours() {
        return linearWindowHours;
    }

    public void setLinearWindowHours(int hours) {
        this.linearWindowHours = Math.max(0, Math.min(24, hours)); // 0 = all data, or 1-24 hours
    }

    public double getRateTrackingIntervalHours() {
        return rateTrackingIntervalHours;
    }

    public void setRateTrackingIntervalHours(double hours) {
        this.rateTrackingIntervalHours = Math.max(0.5, Math.min(6.0, hours)); // 0.5 to 6 hours
    }

    /**
     * Check if at least one formula is enabled
     */
    public boolean hasAnyFormulaEnabled() {
        return linearEnabled || quadraticEnabled || exponentialEnabled ||
                powerLawEnabled || logarithmicEnabled || tangentEnabled || hyperbolicEnabled;
    }
}
