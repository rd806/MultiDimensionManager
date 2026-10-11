package com.github.rd806.mdm.setup;

import com.github.rd806.mdm.MdmMain;
import com.github.rd806.mdm.command.DimensionCommand;
import com.github.rd806.mdm.command.ModifyCommand;
import com.github.rd806.mdm.command.TeleportCommand;
import com.github.rd806.mdm.dimension.manager.DynamicDimensionManager;
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
        MdmMain.LOGGER.info("HELLO from server starting");
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
     * 注册 /mdm 命令树：dimension 命令组和 tp。
     * <p>
     * 两个 builder 的根都是 {@code mdm} 字面量，Brigadier 注册时会合并同名根节点，
     * 所以分两处注册得到的是同一棵 {@code /mdm} 树，不是两条互相覆盖的命令。
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(DimensionCommand.get());
        event.getDispatcher().register(TeleportCommand.get());
        event.getDispatcher().register(ModifyCommand.get());
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
