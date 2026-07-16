package com.placeanywhere.mixin;

import com.placeanywhere.core.FreeBlocks;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.CollisionView;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;











@Mixin(CollisionView.class)
public interface CollisionViewMixin {

    @Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true)
    private void placeanywhere$onGetBlockCollisions(Entity entity, Box box,
                                                    CallbackInfoReturnable<Iterable<VoxelShape>> cir) {
        if (!(this instanceof World world)) return;
        List<VoxelShape> extra = FreeBlocks.collectCollisionShapes(world, box);
        if (extra.isEmpty()) return;
        List<VoxelShape> merged = new ArrayList<>(16);
        cir.getReturnValue().forEach(merged::add);
        merged.addAll(extra);
        cir.setReturnValue(merged);
    }
}
