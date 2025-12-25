package com.queueestimator.config;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/**
 * Configuration screen for Queue Estimator mod.
 * Allows enabling/disabling different curve fitting formulas.
 */
public class QueueEstimatorConfigScreen extends Screen {

    private final Screen parent;
    private final QueueEstimatorConfig config;

    // Toggle buttons
    private ButtonWidget linearButton;
    private ButtonWidget quadraticButton;
    private ButtonWidget exponentialButton;
    private ButtonWidget powerLawButton;
    private ButtonWidget logarithmicButton;
    private ButtonWidget tangentButton;
    private ButtonWidget hyperbolicButton;
    private ButtonWidget showAllButton;

    public QueueEstimatorConfigScreen(Screen parent) {
        super(Text.literal("Queue Estimator Config"));
        this.parent = parent;
        this.config = QueueEstimatorConfig.getInstance();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = 50;
        int buttonWidth = 200;
        int buttonHeight = 20;
        int spacing = 25;

        // Title is rendered in render method

        // Linear toggle
        linearButton = ButtonWidget.builder(
                getToggleText("Linear", config.isLinearEnabled()),
                button -> {
                    config.setLinearEnabled(!config.isLinearEnabled());
                    button.setMessage(getToggleText("Linear", config.isLinearEnabled()));
                }).dimensions(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight).build();
        this.addDrawableChild(linearButton);

        // Quadratic toggle
        quadraticButton = ButtonWidget.builder(
                getToggleText("Quadratic", config.isQuadraticEnabled()),
                button -> {
                    config.setQuadraticEnabled(!config.isQuadraticEnabled());
                    button.setMessage(getToggleText("Quadratic", config.isQuadraticEnabled()));
                }).dimensions(centerX - buttonWidth / 2, startY + spacing, buttonWidth, buttonHeight).build();
        this.addDrawableChild(quadraticButton);

        // Exponential toggle (shifted exponential)
        exponentialButton = ButtonWidget.builder(
                getToggleText("Exponential (A·e^(-Bt) - C)", config.isExponentialEnabled()),
                button -> {
                    config.setExponentialEnabled(!config.isExponentialEnabled());
                    button.setMessage(getToggleText("Exponential (A·e^(-Bt) - C)", config.isExponentialEnabled()));
                }).dimensions(centerX - buttonWidth / 2, startY + spacing * 2, buttonWidth, buttonHeight).build();
        this.addDrawableChild(exponentialButton);

        // Power Law toggle
        powerLawButton = ButtonWidget.builder(
                getToggleText("Power Law (A·t^(-B) + C)", config.isPowerLawEnabled()),
                button -> {
                    config.setPowerLawEnabled(!config.isPowerLawEnabled());
                    button.setMessage(getToggleText("Power Law (A·t^(-B) + C)", config.isPowerLawEnabled()));
                }).dimensions(centerX - buttonWidth / 2, startY + spacing * 3, buttonWidth, buttonHeight).build();
        this.addDrawableChild(powerLawButton);

        // Logarithmic toggle
        logarithmicButton = ButtonWidget.builder(
                getToggleText("Logarithmic (A - B·ln(t+1))", config.isLogarithmicEnabled()),
                button -> {
                    config.setLogarithmicEnabled(!config.isLogarithmicEnabled());
                    button.setMessage(getToggleText("Logarithmic (A - B·ln(t+1))", config.isLogarithmicEnabled()));
                }).dimensions(centerX - buttonWidth / 2, startY + spacing * 4, buttonWidth, buttonHeight).build();
        this.addDrawableChild(logarithmicButton);

        // Tangent toggle
        tangentButton = ButtonWidget.builder(
                getToggleText("Tangent (A·tan(B-kt) - D)", config.isTangentEnabled()),
                button -> {
                    config.setTangentEnabled(!config.isTangentEnabled());
                    button.setMessage(getToggleText("Tangent (A·tan(B-kt) - D)", config.isTangentEnabled()));
                }).dimensions(centerX - buttonWidth / 2, startY + spacing * 5, buttonWidth, buttonHeight).build();
        this.addDrawableChild(tangentButton);

        // Hyperbolic toggle
        hyperbolicButton = ButtonWidget.builder(
                getToggleText("Hyperbolic (A/(t+B) - C)", config.isHyperbolicEnabled()),
                button -> {
                    config.setHyperbolicEnabled(!config.isHyperbolicEnabled());
                    button.setMessage(getToggleText("Hyperbolic (A/(t+B) - C)", config.isHyperbolicEnabled()));
                }).dimensions(centerX - buttonWidth / 2, startY + spacing * 6, buttonWidth, buttonHeight).build();
        this.addDrawableChild(hyperbolicButton);

        // Show all results toggle
        showAllButton = ButtonWidget.builder(
                getToggleText("Show All Results", config.isShowAllResults()),
                button -> {
                    config.setShowAllResults(!config.isShowAllResults());
                    button.setMessage(getToggleText("Show All Results", config.isShowAllResults()));
                }).dimensions(centerX - buttonWidth / 2, startY + spacing * 8, buttonWidth, buttonHeight).build();
        this.addDrawableChild(showAllButton);

        // Ignore first minutes slider
        IgnoreFirstMinutesSlider ignoreSlider = new IgnoreFirstMinutesSlider(
                centerX - buttonWidth / 2,
                startY + spacing * 9,
                buttonWidth,
                buttonHeight,
                config.getIgnoreFirstMinutes());
        this.addDrawableChild(ignoreSlider);

        // Linear window hours slider (0 = all data)
        LinearWindowSlider linearWindowSlider = new LinearWindowSlider(
                centerX - buttonWidth / 2,
                startY + spacing * 10,
                buttonWidth,
                buttonHeight,
                config.getLinearWindowHours());
        this.addDrawableChild(linearWindowSlider);

        // Rate tracking interval slider (hours)
        RateTrackingSlider rateTrackingSlider = new RateTrackingSlider(
                centerX - buttonWidth / 2,
                startY + spacing * 11,
                buttonWidth,
                buttonHeight,
                config.getRateTrackingIntervalHours());
        this.addDrawableChild(rateTrackingSlider);

        // Done button
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("Done"),
                button -> close()).dimensions(centerX - 50, this.height - 30, 100, 20).build());
    }

    private Text getToggleText(String name, boolean enabled) {
        String status = enabled ? "§a✓" : "§c✗";
        return Text.literal(status + " " + name);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        // Draw title
        context.drawCenteredTextWithShadow(
                this.textRenderer,
                this.title,
                this.width / 2,
                20,
                0xFFFFFF);

        // Draw section headers
        context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.literal("§7Curve Fitting Formulas"),
                this.width / 2,
                38,
                0xAAAAAA);

        context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.literal("§7Display Settings"),
                this.width / 2,
                50 + 25 * 7 + 10,
                0xAAAAAA);
    }

    @Override
    public void close() {
        config.save();
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    /**
     * Custom slider for ignore first minutes setting
     */
    private class IgnoreFirstMinutesSlider extends SliderWidget {

        public IgnoreFirstMinutesSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height,
                    Text.literal("Ignore First: " + initialValue + " min"),
                    initialValue / 60.0); // Normalize to 0-1 range (0 to 60)
        }

        @Override
        protected void updateMessage() {
            int value = getValue();
            this.setMessage(Text.literal("Ignore First: " + value + " min"));
        }

        @Override
        protected void applyValue() {
            config.setIgnoreFirstMinutes(getValue());
        }

        private int getValue() {
            return (int) Math.round(this.value * 60); // Convert back to 0-60 range
        }
    }

    /**
     * Custom slider for linear window hours setting (0 = all data)
     */
    private class LinearWindowSlider extends SliderWidget {

        public LinearWindowSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height,
                    initialValue == 0 ? Text.literal("Linear Window: All Data")
                            : Text.literal("Linear Window: " + initialValue + " hr"),
                    initialValue / 24.0); // Normalize to 0-1 range (0 to 24)
        }

        private Text getText(int hours) {
            if (hours == 0) {
                return Text.literal("Linear Window: All Data");
            } else {
                return Text.literal("Linear Window: " + hours + " hr");
            }
        }

        @Override
        protected void updateMessage() {
            int value = getValue();
            this.setMessage(getText(value));
        }

        @Override
        protected void applyValue() {
            config.setLinearWindowHours(getValue());
        }

        private int getValue() {
            return (int) Math.round(this.value * 24); // Convert back to 0-24 range
        }
    }

    /**
     * Custom slider for rate tracking interval setting (hours)
     */
    private class RateTrackingSlider extends SliderWidget {

        public RateTrackingSlider(int x, int y, int width, int height, double initialValue) {
            super(x, y, width, height,
                    Text.literal(String.format("Rate Log Interval: %.1f hr", initialValue)),
                    (initialValue - 0.5) / 5.5); // Normalize to 0-1 range (0.5 to 6.0)
        }

        @Override
        protected void updateMessage() {
            double value = getValue();
            this.setMessage(Text.literal(String.format("Rate Log Interval: %.1f hr", value)));
        }

        @Override
        protected void applyValue() {
            config.setRateTrackingIntervalHours(getValue());
        }

        private double getValue() {
            return Math.round((this.value * 5.5 + 0.5) * 10) / 10.0; // Convert back to 0.5-6.0 range with 0.1 precision
        }
    }
}
