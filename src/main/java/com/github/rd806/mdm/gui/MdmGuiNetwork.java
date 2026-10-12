package com.github.rd806.mdm.gui;

import com.github.rd806.mdm.MdmMain;
import com.github.rd806.mdm.dimension.MdmDimensions;
import com.github.rd806.mdm.dimension.manager.DynamicDimensionManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Comparator;
import java.util.List;

/**
 * 维度选择界面的通道：两个包的外壳，以及两端各自的调用入口。
 * <p>
 * 本模组只此一处网络代码。界面是服务端驱动的：{@code /mdm gui} 在服务端执行，把维度列表随
 * {@link OpenDimensionSelectPacket} 发下去，客户端只负责画和收集点击，点击再通过
 * {@link DimensionTeleportPacket} 回到服务端执行。客户端不自己拼列表，因为动态维度是运行时
 * 直接塞进 {@code MinecraftServer.levels} 的，没进 level stem 注册表，客户端根本看不到。
 * <p>
 * 本类必须双端可加载：任何客户端类型都不能出现在它的签名或字段里，对
 * {@code DimensionSelectScreen} 的唯一引用藏在 {@link OpenDimensionSelectPacket} 的处理器里。
 */
public final class MdmGuiNetwork {

    /** 通道协议版本，两端必须一致，否则握手失败。包体加字段就升版本：宁可连不上，不要错位解析。 */
    private static final String PROTOCOL = "1.1.0";

    /** 与 {@code /mdm} 命令树一致的权限等级：通道不能成为绕过命令权限的后门。 */
    public static final int PERMISSION_LEVEL = 2;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(MdmMain.MODID, "gui"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private MdmGuiNetwork() {
    }

    /** 注册两个包。必须在双端各调用一次，故由 {@code MdmMain} 的构造函数调用。 */
    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(OpenDimensionSelectPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenDimensionSelectPacket::encode)
                .decoder(OpenDimensionSelectPacket::new)
                .consumerMainThread(OpenDimensionSelectPacket::handle)
                .add();
        CHANNEL.messageBuilder(DimensionTeleportPacket.class, id, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DimensionTeleportPacket::encode)
                .decoder(DimensionTeleportPacket::new)
                .consumerMainThread(DimensionTeleportPacket::handle)
                .add();
    }

    /**
     * 把当前全部已加载维度发给该玩家，并让他的客户端打开选择界面。
     * <p>
     * 列表来源与 {@code /mdm dimension list} 完全一致：{@link DynamicDimensionManager#list}
     * 拿全部已加载维度，再用 {@link MdmDimensions#isStatic} 区分静态/动态。排序也照抄那边，
     * 保证界面顺序和命令输出一致。
     */
    public static void openSelectScreen(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        List<OpenDimensionSelectPacket.Entry> entries = DynamicDimensionManager.list(server).stream()
                // 列表在这里就裁掉，客户端只画服务端给的东西，不用自己再判断一次
                .filter(key -> MdmDimensions.isVisible(key))
                .sorted(Comparator.comparing(key -> key.location().toString()))
                .map(key -> new OpenDimensionSelectPacket.Entry(
                        key.location(), MdmDimensions.isStatic(server, key),
                        DynamicDimensionManager.aliasOf(server, key)))
                .toList();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenDimensionSelectPacket(entries));
    }

    /** 客户端点击某个维度后请求传送，校验在服务端。 */
    public static void requestTeleport(ResourceLocation id) {
        CHANNEL.sendToServer(new DimensionTeleportPacket(id));
    }
}
