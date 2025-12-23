package com.queueestimator.mixin;

import com.queueestimator.QueueDataTracker;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class TitlePacketMixin {

    @Inject(method = "onTitle", at = @At("HEAD"))
    private void onTitleReceived(TitleS2CPacket packet, CallbackInfo ci) {
        // TitleS2CPacket is a record in 1.21.4, use text() accessor
        Text titleText = packet.text();
        if (titleText != null) {
            QueueDataTracker.getInstance().processTitleText(titleText);
        }
    }
}
