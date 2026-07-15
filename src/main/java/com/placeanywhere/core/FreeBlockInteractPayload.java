package com.placeanywhere.core;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

public record FreeBlockInteractPayload(int action, double hitX, double hitY, double hitZ,
                                       int sideId, int handId,
                                       float qx, float qy, float qz, float qw,
                                       double pointX, double pointY, double pointZ) implements CustomPayload {
    public static final int ACTION_MINE = 0;
    public static final int ACTION_PLACE = 1;
    public static final int ACTION_USE = 2;

    public static final int ACTION_PLACE_FREE = 3;

    public FreeBlockInteractPayload(int action, double hitX, double hitY, double hitZ, int sideId, int handId) {
        this(action, hitX, hitY, hitZ, sideId, handId, 0f, 0f, 0f, 1f, 0.0, 0.0, 0.0);
    }

    public FreeBlockInteractPayload(int action, double hitX, double hitY, double hitZ,
                                    int sideId, int handId,
                                    float qx, float qy, float qz, float qw) {
        this(action, hitX, hitY, hitZ, sideId, handId, qx, qy, qz, qw, 0.0, 0.0, 0.0);
    }

    public static final CustomPayload.Id<FreeBlockInteractPayload> ID =
            new CustomPayload.Id<>(Identifier.of("placeanywherecore", "interact"));
    public static final PacketCodec<PacketByteBuf, FreeBlockInteractPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
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
                buf.writeDouble(payload.pointX());
                buf.writeDouble(payload.pointY());
                buf.writeDouble(payload.pointZ());
            },
            buf -> new FreeBlockInteractPayload(
                    buf.readVarInt(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readVarInt(), buf.readVarInt(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble()
            )
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

    public Direction side() { return Direction.byId(sideId); }
}
