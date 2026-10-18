package com.casimirbot.helixplayer.fabric.mixin;

import com.casimirbot.helixplayer.fabric.ManualInputLatch;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @Inject(method = "onPress", at = @At("HEAD"))
    private void helix$recordRawMouseInput(
        long window,
        int button,
        int action,
        int modifiers,
        CallbackInfo callback
    ) {
        if (action == 1) ManualInputLatch.recordMousePress(button);
    }
}
