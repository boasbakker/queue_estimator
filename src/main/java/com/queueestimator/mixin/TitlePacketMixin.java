package com.queueestimator.mixin;

import com.queueestimator.QueueDataTracker;
import com.queueestimator.QueueEstimatorMod;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin that hooks into InGameHud.setTitle() to detect when titles are
 * displayed.
 * This is more robust than intercepting network packets as it catches all title
 * displays
 * regardless of how they are triggered (packets, commands, mods, etc.)
 */
@Mixin(InGameHud.class)
public class TitlePacketMixin {

    /**
     * Called whenever a title is set to be displayed on screen.
     * This triggers for all title displays, not just network packets.
     */
    @Inject(method = "setTitle", at = @At("HEAD"))
    private void onTitleSet(Text title, CallbackInfo ci) {
        if (title != null) {
            QueueEstimatorMod.LOGGER.debug("Title detected: {}", title.getString());
            QueueDataTracker.getInstance().processTitleText(title);
        }
    }

    /**
     * Also hook into setSubtitle in case queue position is shown as a subtitle.
     */
    @Inject(method = "setSubtitle", at = @At("HEAD"))
    private void onSubtitleSet(Text subtitle, CallbackInfo ci) {
        if (subtitle != null) {
            QueueEstimatorMod.LOGGER.debug("Subtitle detected: {}", subtitle.getString());
            QueueDataTracker.getInstance().processTitleText(subtitle);
        }
    }
}
