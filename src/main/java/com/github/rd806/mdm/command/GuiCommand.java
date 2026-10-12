package com.github.rd806.mdm.command;

import com.github.rd806.mdm.gui.MdmGuiNetwork;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /mdm gui}：打开维度选择界面。
 * <p>
 * 命令本身在服务端，只负责把列表随包发下去，界面由客户端负责画——所以控制台执行会报
 * "只有玩家能执行"（没有客户端可开）。
 * <p>
 * 权限要求和 {@code /mdm tp} 相同：能开界面的人本来就能用命令传送。传送包那一侧还会再校验
 * 一次，不能指望这条 {@code requires} 挡住改客户端的玩家。
 */
public final class GuiCommand {

    private static final String ROOT = "mdm";
    private static final String GUI = "gui";

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal(ROOT)
                .requires(source -> source.hasPermission(MdmGuiNetwork.PERMISSION_LEVEL))
                .then(Commands.literal(GUI).executes(GuiCommand::open));
    }

    private static int open(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        MdmGuiNetwork.openSelectScreen(context.getSource().getPlayerOrException());
        return 1;
    }
}
