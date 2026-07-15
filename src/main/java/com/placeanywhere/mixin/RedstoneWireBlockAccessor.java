package com.placeanywhere.mixin;

import net.minecraft.block.RedstoneWireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RedstoneWireBlock.class)
public interface RedstoneWireBlockAccessor {

    @Accessor("wiresGivePower")
    void placeanywhere$setWiresGivePower(boolean value);

    @Accessor("wiresGivePower")
    boolean placeanywhere$getWiresGivePower();
}
