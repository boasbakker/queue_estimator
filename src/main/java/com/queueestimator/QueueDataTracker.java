package com.queueestimator;

import com.queueestimator.config.QueueEstimatorConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks queue position data points over time and estimates time to enter.
 * Records data at constant frequency (every title packet received) to ensure
 * proper time-series data for curve fitting.
 */
public class QueueDataTracker {
    private static QueueDataTracker instance;
    
    // Regex pattern to extract queue position - handles color codes and variations
    // Matches "Position in queue: X" with optional color codes (§X) or other formatting
    private static final Pattern QUEUE_PATTERN = Pattern.compile(
        "(?:§.)*Position\\s+in\\s+queue[:\\s]+(?:§.)*([0-9]+)",
        Pattern.CASE_INSENSITIVE
    );
    
    // Alternative pattern for stripped text
    private static final Pattern QUEUE_PATTERN_STRIPPED = Pattern.compile(
        "Position\\s+in\\s+queue[:\\s]+([0-9]+)",
        Pattern.CASE_INSENSITIVE
    );
    
    private final List<DataPoint> dataPoints = new ArrayList<>();
    private int lastPosition = -1;
    private long sessionStartTime = -1;
    private long lastRecordTime = -1;
    private Path csvLogPath = null;
    private static final DateTimeFormatter CSV_FILENAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    
    // Minimum interval between recordings (in ms) to avoid spam but maintain frequency
    // Set to 5 seconds - most queue servers update every 5-60 seconds
    private static final long MIN_RECORD_INTERVAL_MS = 5000;
    
    // Maximum data points to keep (to prevent memory issues in very long queues)
    private static final int MAX_DATA_POINTS = 1000;
    
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    
    private QueueDataTracker() {}
    
    public static QueueDataTracker getInstance() {
        if (instance == null) {
            instance = new QueueDataTracker();
        }
        return instance;
    }
    
    /**
     * Data point containing timestamp and queue position
     */
    public static class DataPoint {
        public final long timestamp; // milliseconds since session start
        public final int position;
        
        public DataPoint(long timestamp, int position) {
            this.timestamp = timestamp;
            this.position = position;
        }
    }
    
    /**
     * Process incoming title text to extract queue position.
     * Records data point every time a valid queue position is received,
     * allowing duplicates to maintain constant time-series frequency.
     */
    public void processTitleText(Text text) {
        // Get the raw string representation which may contain color codes
        String rawText = text.getString();
        
        Integer position = extractQueuePosition(rawText);
        
        if (position != null) {
            long currentTime = System.currentTimeMillis();
            
            // Record if enough time has passed since last recording
            // This allows duplicates but prevents spam if server sends rapid updates
            if (lastRecordTime < 0 || (currentTime - lastRecordTime) >= MIN_RECORD_INTERVAL_MS) {
                recordPosition(position);
                lastPosition = position;
                lastRecordTime = currentTime;
            }
        }
    }
    
    /**
     * Extract queue position from text, handling various formatting
     */
    private Integer extractQueuePosition(String text) {
        // Try with color codes first
        Matcher matcher = QUEUE_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        // Try stripped version
        matcher = QUEUE_PATTERN_STRIPPED.matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        
        return null;
    }
    
    /**
     * Record a new position data point (including duplicates for constant frequency)
     */
    private void recordPosition(int position) {
        long currentTime = System.currentTimeMillis();
        
        // Initialize session start time on first data point
        if (sessionStartTime < 0) {
            sessionStartTime = currentTime;
            dataPoints.clear();
            initializeCsvLog();
        }
        
        long relativeTime = currentTime - sessionStartTime;
        dataPoints.add(new DataPoint(relativeTime, position));
        
        // Log to CSV
        logToCsv(currentTime, relativeTime, position);
        
        // Trim old data points if we have too many
        while (dataPoints.size() > MAX_DATA_POINTS) {
            dataPoints.remove(0);
        }
        
        QueueEstimatorMod.LOGGER.info("Queue position recorded: {} at t={}ms (total points: {})", 
            position, relativeTime, dataPoints.size());
        
        // Try to estimate and display if we have enough data
        QueueEstimatorConfig config = QueueEstimatorConfig.getInstance();
        if (dataPoints.size() >= config.getMinDataPoints()) {
            estimateAndDisplay();
        } else {
            int needed = config.getMinDataPoints() - dataPoints.size();
            sendChatMessage(String.format("§7[Queue] Position: §f%d §7| Collecting data... §8(%d more needed)", 
                position, needed));
        }
    }
    
    /**
     * Initialize CSV log file for this session
     */
    private void initializeCsvLog() {
        try {
            // Create queue_logs folder in game directory
            Path gameDir = Paths.get(System.getProperty("user.dir"));
            Path logsDir = gameDir.resolve("queue_logs");
            Files.createDirectories(logsDir);
            
            // Create CSV file with timestamp
            String filename = "queue_" + LocalDateTime.now().format(CSV_FILENAME_FORMATTER) + ".csv";
            csvLogPath = logsDir.resolve(filename);
            
            // Write CSV header
            try (BufferedWriter writer = Files.newBufferedWriter(csvLogPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write("timestamp,relative_time_ms,position");
                writer.newLine();
            }
            
            QueueEstimatorMod.LOGGER.info("CSV log initialized: {}", csvLogPath);
        } catch (IOException e) {
            QueueEstimatorMod.LOGGER.error("Failed to initialize CSV log", e);
            csvLogPath = null;
        }
    }
    
    /**
     * Log a data point to the CSV file
     */
    private void logToCsv(long absoluteTime, long relativeTime, int position) {
        if (csvLogPath == null) {
            return;
        }
        
        try (BufferedWriter writer = Files.newBufferedWriter(csvLogPath, StandardOpenOption.APPEND)) {
            // Format: ISO timestamp, relative time in ms, position
            String isoTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(absoluteTime), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            writer.write(String.format("%s,%d,%d", isoTime, relativeTime, position));
            writer.newLine();
        } catch (IOException e) {
            QueueEstimatorMod.LOGGER.error("Failed to write to CSV log", e);
        }
    }
    
    /**
     * Perform curve fitting and display estimate
     */
    private void estimateAndDisplay() {
        try {
            QueueEstimatorConfig config = QueueEstimatorConfig.getInstance();
            
            if (!config.hasAnyFormulaEnabled()) {
                int currentPosition = dataPoints.get(dataPoints.size() - 1).position;
                sendChatMessage(String.format(
                        "§e[Queue] Position: §f%d §e| No formulas enabled in config!",
                        currentPosition));
                return;
            }

            MultiFormulaCurveFitter fitter = new MultiFormulaCurveFitter(dataPoints);
            MultiFormulaCurveFitter.MultiResult multiResult = fitter.fitAll();

            int currentPosition = dataPoints.get(dataPoints.size() - 1).position;
            List<MultiFormulaCurveFitter.FitResult> validResults = multiResult.getValidResults();

            if (validResults.isEmpty()) {
                sendChatMessage(String.format(
                        "§e[Queue] Position: §f%d §e| All curve fits failed",
                        currentPosition));
                return;
            }

            if (config.isShowAllResults()) {
                // Show all valid results
                sendChatMessage(String.format("§a[Queue] Position: §f%d §a| §7%d fit(s) succeeded:",
                        currentPosition, validResults.size()));

                for (MultiFormulaCurveFitter.FitResult result : validResults) {
                    String timeStr = formatEta(result.etaMs);
                    long remainingMinutes = result.etaMs / 60000;
                    
                    sendChatMessage(String.format("  §7%s: §fETA %s §7(~%d min) §8| %%RMSE: §f%.1f%%",
                            result.type.displayName,
                            timeStr,
                            remainingMinutes,
                            result.relativeRMSE));
                }
            } else {
                // Show only the best result
                MultiFormulaCurveFitter.FitResult best = multiResult.best;
                if (best != null) {
                    String timeStr = formatEta(best.etaMs);
                    long remainingMinutes = best.etaMs / 60000;

                    sendChatMessage(String.format(
                            "§a[Queue] Position: §f%d §a| ETA: §f%s §7(~%d min) §a| %s §8| %%RMSE: §f%.1f%%",
                            currentPosition,
                            timeStr,
                            remainingMinutes,
                            best.type.displayName,
                            best.relativeRMSE));
                }
            }

        } catch (Exception e) {
            QueueEstimatorMod.LOGGER.error("Error during curve fitting", e);
            int currentPosition = dataPoints.get(dataPoints.size() - 1).position;
            sendChatMessage(String.format(
                "§c[Queue] Position: §f%d §c| ETA: §7Error",
                currentPosition
            ));
        }
    }
    
    /**
     * Format ETA from milliseconds to time string
     */
    private String formatEta(long etaMs) {
        if (etaMs <= 0) {
            return "Unknown";
        }
        LocalDateTime estimatedTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(System.currentTimeMillis() + etaMs),
                ZoneId.systemDefault());
        return estimatedTime.format(TIME_FORMATTER);
    }

    /**
     * Send a message to the player's chat
     */
    private void sendChatMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }
    
    /**
     * Reset the tracker for a new session
     */
    public void reset() {
        dataPoints.clear();
        lastPosition = -1;
        sessionStartTime = -1;
        lastRecordTime = -1;
        csvLogPath = null;
        QueueEstimatorMod.LOGGER.info("Queue tracker reset");
    }
    
    /**
     * Get current data points (for debugging)
     */
    public List<DataPoint> getDataPoints() {
        return new ArrayList<>(dataPoints);
    }
}
