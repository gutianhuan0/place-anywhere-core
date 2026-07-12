package com.placeanywhere.client.mixin;

import com.placeanywhere.PlaceAnywhereMod;
import com.placeanywhere.core.FreeBlockInteractPayload;
import com.placeanywhere.core.FreeBlocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 直接在 MinecraftClient 的左键/右键处理方法中注入，绕过 crosshairTarget 机制。
 *
 * 左键：handleBlockBreaking 每帧调用，只在"开始破坏"那一帧发一次 MINE 包。
 * 右键：doItemUse 每次按下触发一次，符合预期。
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    private static final double REACH = 6.0;
    /** 右键冷却间隔（ms）：防止按住右键时连续发包，但松开后再按能立即触发。 */
    private static final long USE_COOLDOWN_MS = 200L;
    /** 上次左键是否已处理，避免按住时每帧重复挖。 */
    private boolean pa$mineHandled = false;
    /** 上次右键处理的时间戳（ms）。用时间戳冷却替代布尔标志，避免连续点击被永久吞掉。 */
    private long pa$lastUseMs = 0L;

    /** 左键挖掘：handleBlockBreaking(boolean breaking) HEAD。
     *  breaking=true 表示正在破坏，false 表示停止。 */
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
        PacketByteBuf buf = PacketByteBufs.create();
        FreeBlockInteractPayload.encode(new FreeBlockInteractPayload(
                FreeBlockInteractPayload.ACTION_MINE,
                fb.pos().x(), fb.pos().y(), fb.pos().z(),
                fb.side().getId(), 0), buf);
        ClientPlayNetworking.send(FreeBlockInteractPayload.ID, buf);
        ci.cancel();
    }

    /** 右键使用：doItemUse() HEAD。
     *  用时间戳冷却替代布尔标志：每次冷却期过后都能再次触发，
     *  不再依赖 raycast 未命中来重置标志，修复"右键没反应"问题。
     *  注意：自由放置模式激活时，由 FreePlacementMode 的 Mixin（优先级 1500）先处理，
     *  本 Mixin（优先级 1000）不会被调用。 */
    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void placeanywhere$onDoItemUse(CallbackInfo ci) {
        MinecraftClient self = (MinecraftClient) (Object) this;
        ClientPlayerEntity player = self.player;
        if (player == null || player.getWorld() == null) return;
        // 右键未按下时放行原版
        if (!self.options.useKey.isPressed()) {
            return;
        }
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(REACH));
        var hit = FreeBlocks.raycast(player.getWorld(), eye, end);
        if (hit.isEmpty()) {
            // 未命中自由方块，放行原版
            return;
        }
        // 命中自由方块：替换 crosshairTarget，让原版/其他模组也认为准星指向自由方块
        var fb = hit.get();
        self.crosshairTarget = new net.minecraft.util.hit.BlockHitResult(
                fb.point(), fb.side(), fb.pos().toBlockPos(), false);
        // 冷却：防止按住右键时连续发包，但冷却期过后允许再次触发
        long now = System.currentTimeMillis();
        if (now - pa$lastUseMs < USE_COOLDOWN_MS) {
            ci.cancel();
            return;
        }
        pa$lastUseMs = now;
        Hand hand = player.getMainHandStack().isEmpty() ? Hand.OFF_HAND : Hand.MAIN_HAND;
        boolean holdingBlock = player.getStackInHand(hand).getItem() instanceof BlockItem;
        int action = holdingBlock
                ? FreeBlockInteractPayload.ACTION_PLACE
                : FreeBlockInteractPayload.ACTION_USE;
        PlaceAnywhereMod.LOGGER.info("[PA-Client] {} @ {},{},{}",
                holdingBlock ? "PLACE" : "USE", fb.pos().x(), fb.pos().y(), fb.pos().z());
        PacketByteBuf buf = PacketByteBufs.create();
        FreeBlockInteractPayload.encode(new FreeBlockInteractPayload(
                action,
                fb.pos().x(), fb.pos().y(), fb.pos().z(),
                fb.side().getId(),
                hand == Hand.OFF_HAND ? 1 : 0), buf);
        ClientPlayNetworking.send(FreeBlockInteractPayload.ID, buf);
        ci.cancel();
    }
}
