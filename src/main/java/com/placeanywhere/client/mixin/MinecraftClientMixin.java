package com.placeanywhere.client.mixin;

import com.placeanywhere.PlaceAnywhereMod;
import com.placeanywhere.core.FreeBlockInteractPayload;
import com.placeanywhere.core.FreeBlocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;







@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    private static final double REACH = 6.0;
    
    private static final long USE_COOLDOWN_MS = 200L;
    
    private boolean pa$mineHandled = false;
    
    private long pa$lastUseMs = 0L;

    

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void placeanywhere$onHandleBlockBreaking(boolean breaking, CallbackInfo ci) {
        MinecraftClient self = (MinecraftClient) (Object) this;
        ClientPlayerEntity player = self.player;
        if (player == null || player.getWorld() == null) return;

        if (!breaking) {
            pa$mineHandled = false;
            return;
        }

        if (pa$mineHandled) {
            ci.cancel();
            return;
        }

        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(REACH));
        var hit = FreeBlocks.raycast(player.getWorld(), eye, end);
        if (hit.isEmpty()) {
            return;
        }
        pa$mineHandled = true;
        var fb = hit.get();
        PlaceAnywhereMod.LOGGER.info("[PA-Client] MINE @ {},{},{}", fb.pos().x(), fb.pos().y(), fb.pos().z());
        ClientPlayNetworking.send(new FreeBlockInteractPayload(
                FreeBlockInteractPayload.ACTION_MINE,
                fb.pos().x(), fb.pos().y(), fb.pos().z(),
                fb.side().getId(), 0));
        ci.cancel();
    }

    




    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void placeanywhere$onDoItemUse(CallbackInfo ci) {
        MinecraftClient self = (MinecraftClient) (Object) this;
        ClientPlayerEntity player = self.player;
        if (player == null || player.getWorld() == null) return;
        
        if (!self.options.useKey.isPressed()) {
            return;
        }
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(REACH));
        var hit = FreeBlocks.raycast(player.getWorld(), eye, end);
        if (hit.isEmpty()) {
            
            return;
        }
        
        var fb = hit.get();
        self.crosshairTarget = new net.minecraft.util.hit.BlockHitResult(
                fb.point(), fb.side(), fb.pos().toBlockPos(), false);
        
        long now = System.currentTimeMillis();
        if (now - pa$lastUseMs < USE_COOLDOWN_MS) {
            ci.cancel();
            return;
        }
        pa$lastUseMs = now;
        Hand hand = player.getMainHandStack().isEmpty() ? Hand.OFF_HAND : Hand.MAIN_HAND;
        
        
        
        
        PlaceAnywhereMod.LOGGER.info("[PA-Client] USE @ {},{},{}",
                fb.pos().x(), fb.pos().y(), fb.pos().z());
        ClientPlayNetworking.send(new FreeBlockInteractPayload(
                FreeBlockInteractPayload.ACTION_USE,
                fb.pos().x(), fb.pos().y(), fb.pos().z(),
                fb.side().getId(),
                hand == Hand.OFF_HAND ? 1 : 0,
                0f, 0f, 0f, 1f,
                fb.point().x, fb.point().y, fb.point().z));
        ci.cancel();
    }
}
