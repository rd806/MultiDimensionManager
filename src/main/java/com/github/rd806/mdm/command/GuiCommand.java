package com.github.rd806.mdm.command;

import com.github.rd806.mdm.gui.MdmGuiNetwork;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /mdm gui}：打开维度选择界面。
 * <p>
 * 命令本身在服务端，只负责把列表随包发下去，界面由客户端负责画——所以控制台执行会报
 * "只有玩家能执行"（没有客户端可开）。
 * <p>
 * <b>它的门槛不是权限，而是"你站在哪个维度"</b>：只有当前维度在
 * {@code Config.DIMENSIONS_WHITELISTS} 里的玩家能开，普通玩家和 OP 一视同仁。判定实现在
 * {@link MdmGuiNetwork#canOpenGui(ServerPlayer)}，GUI 里点击传送的那个包复用同一条规则。
 */
public final class GuiCommand {

    private static final String ROOT = "mdm";
    private static final String GUI = "gui";

    /**
     * 根节点上<b>不要</b>挂 {@code requires}：四个命令类注册的是同一棵 {@code /mdm} 树，
     * Brigadier 合并同名节点时只保留第一个注册的 requirement（详见
     * {@code ServerSetup#onRegisterCommands}），挂在这里的谓词会统治整棵树。
     */
    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal(ROOT)
                .then(Commands.literal(GUI).executes(GuiCommand::open));
    }

    private static int open(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();

        // 判定放这里而不是 requires 里：requires 是个纯谓词，失败时 Brigadier 只会回一句含糊的
        // "未知或不完整的命令"，没法告诉玩家为什么开不了
        if (!MdmGuiNetwork.canOpenGui(player)) {
            source.sendFailure(Component.translatable("message.mdm.command.gui.not_allowed",
                    player.level().dimension().location()));
            return 0;
        }
        MdmGuiNetwork.openSelectScreen(player);
        return 1;
    }
}
