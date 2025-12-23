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

        // Show all results toggle
        showAllButton = ButtonWidget.builder(
                getToggleText("Show All Results", config.isShowAllResults()),
                button -> {
                    config.setShowAllResults(!config.isShowAllResults());
                    button.setMessage(getToggleText("Show All Results", config.isShowAllResults()));
                }).dimensions(centerX - buttonWidth / 2, startY + spacing * 6, buttonWidth, buttonHeight).build();
        this.addDrawableChild(showAllButton);

        // Min data points slider
        MinDataPointsSlider slider = new MinDataPointsSlider(
                centerX - buttonWidth / 2,
                startY + spacing * 7,
                buttonWidth,
                buttonHeight,
                config.getMinDataPoints());
        this.addDrawableChild(slider);

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
                50 + 25 * 5 + 10,
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
     * Custom slider for minimum data points setting
     */
    private class MinDataPointsSlider extends SliderWidget {

        public MinDataPointsSlider(int x, int y, int width, int height, int initialValue) {
            super(x, y, width, height,
                    Text.literal("Min Data Points: " + initialValue),
                    (initialValue - 3) / 17.0); // Normalize to 0-1 range (3 to 20)
        }

        @Override
        protected void updateMessage() {
            int value = getValue();
            this.setMessage(Text.literal("Min Data Points: " + value));
        }

        @Override
        protected void applyValue() {
            config.setMinDataPoints(getValue());
        }

        private int getValue() {
            return (int) Math.round(this.value * 17) + 3; // Convert back to 3-20 range
        }
    }
}
