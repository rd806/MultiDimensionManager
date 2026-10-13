package com.github.rd806.mdm.gui;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：打开维度选择界面，并带上要展示的全部条目。
 *
 * @param entries 已排序的维度条目
 */
public record OpenDimensionSelectPacket(List<Entry> entries) {

    /**
     * 一个维度条目。
     *
     * @param isStatic 是否由数据包 JSON 定义（否则是本模组运行时创建/导入的动态维度）
     * @param alias    管理员设的别名，界面上当主标题显示；没设过为 null。任何已加载维度都能设，
     *                 所以静态维度也可能有
     */
    public record Entry(ResourceLocation id, boolean isStatic, @Nullable String alias) {
    }

    public OpenDimensionSelectPacket(FriendlyByteBuf buf) {
        this(buf.readList(reader -> new Entry(reader.readResourceLocation(), reader.readBoolean(),
                reader.readNullable(FriendlyByteBuf::readUtf))));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeCollection(entries, (writer, entry) -> {
            writer.writeResourceLocation(entry.id());
            writer.writeBoolean(entry.isStatic());
            writer.writeNullable(entry.alias(), FriendlyByteBuf::writeUtf);
        });
    }

    /**
     * 客户端处理器。
     * <p>
     * 注册时用的是 {@code consumerMainThread}，它内部已经把处理器包进
     * {@code enqueueWork} 并置了 {@code setPacketHandled(true)}，所以这里**不要**再排一次队。
     * <p>
     * 这是全模组唯一引用 {@code DimensionSelectScreen} 的地方，而且藏在 lambda 体内——类加载器
     * 只在 lambda 真正执行时才去解析那个客户端类，而这个处理器只会在收到 PLAY_TO_CLIENT 包时
     * 执行，专用服务端永远不会走到。外层的 {@code DistExecutor} 是第二道保险：万一有人误在
     * 服务端调用了这里，它也不会去加载客户端类。
     */
    public void handle(Supplier<NetworkEvent.Context> context) {
        List<Entry> entries = this.entries;
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> DimensionSelectScreen.open(entries));
    }
}
