package com.github.rd806.mdm.gui;

import com.github.rd806.mdm.dimension.MdmDimensions;
import com.github.rd806.mdm.dimension.manager.DynamicDimensionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：玩家在界面里点了某个维度，请求传送到那儿。
 * <p>
 * 只发一个维度 id，传送落点、权限、维度是否存在全部由服务端决定——客户端说的只算"意图"。
 *
 * @param id 目标维度 id
 */
public record DimensionTeleportPacket(ResourceLocation id) {

    public DimensionTeleportPacket(FriendlyByteBuf buf) {
        this(buf.readResourceLocation());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(id);
    }

    /**
     * 服务端处理器。
     * <p>
     * 注册时用的是 {@code consumerMainThread}，处理器本身已经在服务器主线程上跑
     * （它内部包了 {@code enqueueWork} 并置了 {@code setPacketHandled(true)}），所以这里不需要
     * 再排队，也不能在别的线程上碰 level。
     */
    public void handle(Supplier<NetworkEvent.Context> context) {
        teleport(context.get().getSender(), this.id);
    }

    /**
     * 服务端侧的实际处理，跑在服务器主线程上。
     * <p>
     * 权限在这里重新校验一遍：包是客户端发来的，能改客户端的玩家可以自己构造它，所以
     * {@code /mdm gui} 上的 {@code requires} 不能当作唯一防线。等级取
     * {@link MdmGuiNetwork#PERMISSION_LEVEL}，与命令树保持一致。
     * <p>
     * 落点传 {@code null}，即"用该维度配置的传送出生点，没配就退到地表"——和
     * {@code /mdm tp <玩家> <维度>} 不带坐标完全同一条路径。
     */
    private static void teleport(ServerPlayer player, ResourceLocation id) {
        // C2S 包理论上必带 sender，防御性判断，避免异常包把主线程炸了
        if (player == null) {
            return;
        }
        if (!player.hasPermissions(MdmGuiNetwork.PERMISSION_LEVEL)) {
            player.displayClientMessage(
                    Component.translatable("message.mdm.command.gui.no_permission"), false);
            return;
        }

        ServerLevel target = player.getServer() == null
                ? null
                : player.getServer().getLevel(MdmDimensions.levelKey(id));
        if (target == null || !DynamicDimensionManager.teleport(player, target.dimension(), null)) {
            player.displayClientMessage(
                    Component.translatable("message.mdm.command.tp.failed", id), false);
            return;
        }
        player.displayClientMessage(
                Component.translatable("message.mdm.command.tp.success", 1, id), false);
    }
}
