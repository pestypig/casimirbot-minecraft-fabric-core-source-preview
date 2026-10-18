package com.casimirbot.helixplayer.fabric.mixin;

import com.casimirbot.helixplayer.fabric.ManualInputLatch;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"))
    private void helix$recordRawKeyboardInput(
        long window,
        int key,
        int scanCode,
        int action,
        int modifiers,
        CallbackInfo callback
    ) {
        if (action != 1) return;

        Minecraft minecraft = Minecraft.getInstance();
        Options options = minecraft.options;

        // Chat is a steering surface. Opening it, switching windows, and typing
        // in it must not preempt the workflow before the Helix mailbox event is
        // delivered. Matching happens against the player's actual bindings so
        // remapped gameplay controls retain manual-takeover semantics.
        if (
            minecraft.screen instanceof ChatScreen ||
            matches(options.keyChat, key, scanCode) ||
            matches(options.keyCommand, key, scanCode) ||
            matches(options.keyPlayerList, key, scanCode)
        ) return;

        ManualInputLatch.KeyboardAction input = null;
        if (matches(options.keyUp, key, scanCode)) {
            input = ManualInputLatch.KeyboardAction.FORWARD;
        } else if (matches(options.keyDown, key, scanCode)) {
            input = ManualInputLatch.KeyboardAction.BACK;
        } else if (matches(options.keyLeft, key, scanCode)) {
            input = ManualInputLatch.KeyboardAction.LEFT;
        } else if (matches(options.keyRight, key, scanCode)) {
            input = ManualInputLatch.KeyboardAction.RIGHT;
        } else if (matches(options.keyJump, key, scanCode)) {
            input = ManualInputLatch.KeyboardAction.JUMP;
        } else if (matches(options.keySprint, key, scanCode)) {
            input = ManualInputLatch.KeyboardAction.SPRINT;
        } else if (
            matches(options.keyShift, key, scanCode) ||
            matches(options.keyInventory, key, scanCode) ||
            matches(options.keySwapOffhand, key, scanCode) ||
            matches(options.keyDrop, key, scanCode) ||
            matches(options.keyUse, key, scanCode) ||
            matches(options.keyAttack, key, scanCode) ||
            matches(options.keyPickItem, key, scanCode) ||
            matchesAny(options.keyHotbarSlots, key, scanCode)
        ) {
            input = ManualInputLatch.KeyboardAction.OTHER_GAMEPLAY;
        }
        ManualInputLatch.recordKeyboardAction(input);
    }

    private static boolean matches(KeyMapping mapping, int key, int scanCode) {
        return mapping != null && mapping.matches(key, scanCode);
    }

    private static boolean matchesAny(KeyMapping[] mappings, int key, int scanCode) {
        if (mappings == null) return false;
        for (KeyMapping mapping : mappings) {
            if (matches(mapping, key, scanCode)) return true;
        }
        return false;
    }
}
