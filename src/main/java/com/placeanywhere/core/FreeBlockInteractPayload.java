package com.placeanywhere.core;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 自由方块交互 C2S payload。
 *
 * 客户端在 attackBlock / interactBlock 中检测到 raycast 命中自由方块时，
 * 通过此包把操作意图发给服务端，由服务端权威执行（挖掘/贴附放置/方块交互）。
 *
 * 操作类型：
 *   MINE  —— 挖掘（命中点：被挖方块的小数坐标）
 *   PLACE —— 贴着放置（命中点：被贴方块的小数坐标；side：贴附面）
 *   USE   —— 交互（命中点：被交互方块的小数坐标；side：命中面；hand：手）
 */
public record FreeBlockInteractPayload(int action, double hitX, double hitY, double hitZ,
                                       int sideId, int handId,
                                       float qx, float qy, float qz, float qw) {
    public static final int ACTION_MINE = 0;
    public static final int ACTION_PLACE = 1;
    public static final int ACTION_USE = 2;
    /** 自由放置模式：在准星位置放置带四元数旋转的方块。 */
    public static final int ACTION_PLACE_FREE = 3;

    /** 向后兼容：无四元数（identity）。 */
    public FreeBlockInteractPayload(int action, double hitX, double hitY, double hitZ, int sideId, int handId) {
        this(action, hitX, hitY, hitZ, sideId, handId, 0f, 0f, 0f, 1f);
    }

    public static final ResourceLocation ID = new ResourceLocation("placeanywhere", "interact");

    public static void encode(FreeBlockInteractPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.action());
        buf.writeDouble(payload.hitX());
        buf.writeDouble(payload.hitY());
        buf.writeDouble(payload.hitZ());
        buf.writeVarInt(payload.sideId());
        buf.writeVarInt(payload.handId());
        buf.writeFloat(payload.qx());
        buf.writeFloat(payload.qy());
        buf.writeFloat(payload.qz());
        buf.writeFloat(payload.qw());
    }

    public static FreeBlockInteractPayload decode(FriendlyByteBuf buf) {
        return new FreeBlockInteractPayload(
                buf.readVarInt(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readVarInt(), buf.readVarInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()
        );
    }

    public static void handle(FreeBlockInteractPayload payload, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                FreeBlockInteractHandler.handle(payload, player);
            }
        });
        ctx.setPacketHandled(true);
    }

    public Direction side() { return Direction.from3DDataValue(sideId); }
}
