package com.placeanywhere.client.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;







@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {

    @Shadow
    private boolean autoJumpEnabled;
    @Shadow
    private int ticksToNextAutojump;

    @Shadow
    protected abstract boolean shouldAutoJump();

    @Inject(method = "autoJump(FF)V", at = @At("HEAD"), cancellable = true)
    private void placeanywhere$onAutoJump(float movementX, float movementZ, CallbackInfo ci) {

        if (!shouldAutoJump()) return;
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;

        if (FreeBlocks.hasAutoJumpObstacle(self)) {
            self.jump();

            ticksToNextAutojump = 10;
            ci.cancel();
        }
    }
}
