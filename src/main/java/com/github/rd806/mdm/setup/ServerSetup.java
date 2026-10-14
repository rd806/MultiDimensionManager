package com.github.rd806.mdm.setup;

import com.github.rd806.mdm.MdmMain;
import com.github.rd806.mdm.command.DimensionCommand;
import com.github.rd806.mdm.command.ModifyCommand;
import com.github.rd806.mdm.command.TeleportCommand;
import com.github.rd806.mdm.dimension.manager.DynamicDimensionManager;
import com.github.rd806.mdm.command.GuiCommand;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;

/**
 * 服务器生命周期与命令注册的订阅方，这些方法必须是 {@code static}。
 */
@Mod.EventBusSubscriber
public class ServerSetup {
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        MdmMain.LOGGER.info("Enable Multi Dimension Manager!");
    }

    /**
     * 服务器起来之后重建上次运行时创建的维度。
     * <p>
     * 用 ServerStartedEvent 而不是 ServerAboutToStartEvent：此刻主世界已经存在于
     * MinecraftServer.levels 里，DynamicDimensionManager 依赖它取存档数据与世界边界。
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        int restored = DynamicDimensionManager.restore(event.getServer());
        if (restored > 0) {
            MdmMain.LOGGER.info("Restored {} dynamic dimension(s)", restored);
        }
    }

    /**
     * 注册 /mdm 命令树：dimension 命令组、tp、modify 和 gui。
     * <p>
     * 每个 builder 的根都是 {@code mdm} 字面量，Brigadier 注册时会合并同名根节点，
     * 所以分多处注册得到的是同一棵 {@code /mdm} 树，不是互相覆盖的命令。
     * <p>
     * <b>合并规则有个坑：{@code CommandNode#addChild} 遇到同名子节点时丢弃新节点、只递归合并
     * 它的子节点，而 requirement 是 final 字段——也就是说根节点的 {@code requires} 只认<b>第一个
     * 注册的</b>，后面几个类写在根上的 requires 会被静默扔掉。</b>所以：
     * <ul>
     *   <li>权限要求一律挂在各自的子命令字面量上（{@code dimension} / {@code tp} / {@code modify}），
     *       别挂根节点——挂上去的话第一个注册的会盖住整棵树；</li>
     *   <li>{@code gui} 故意不挂，它的门槛是维度白名单，判定在
     *       {@code MdmGuiNetwork#canOpenGui} 里，命令和传送包共用同一条规则。</li>
     * </ul>
     * 换句话说，这里四行的顺序不影响权限，别"顺手"把 requires 加回根节点。
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(DimensionCommand.get());
        event.getDispatcher().register(TeleportCommand.get());
        event.getDispatcher().register(ModifyCommand.get());
        event.getDispatcher().register(GuiCommand.get());
    }

    /**
     * 服务器停掉后清空维度快照。
     * <p>
     * {@code MdmMain.LEVELS} 是静态的（per-JVM），单人模式退出存档再进另一个存档时会残留
     * 上一个世界的维度 key——候选补全就会提示一堆根本不存在的维度。在这里清掉。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MdmMain.LEVELS = Set.of();
    }
}
