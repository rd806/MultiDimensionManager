package com.github.rd806.mdm.command;

import com.github.rd806.mdm.dimension.MdmDimensions;
import com.github.rd806.mdm.dimension.manager.DynamicDimensionManager;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;

/**
 * {@code /mdm tp ...} 命令。
 * <pre>
 *   /mdm tp &lt;目标&gt; &lt;维度&gt; [坐标]
 * </pre>
 * <p>
 * 不给坐标时落点由 {@link DynamicDimensionManager#teleport} 决定：优先用该维度配置的传送
 * 出生点（见 {@code /mdm dimension spawn}），没有则退到玩家当前 X/Z 对应的地表。
 * <p>
 * 维度参数故意用 {@code DimensionArgument} 而不是 {@code MdmMain.LEVELS} 的补全：这里可以传
 * 送到任何已加载的维度，包括主世界/下界/末地和 datapack 定义的静态维度。权限要求挂在根节点
 * {@code mdm} 上，由 Brigadier 向下传递。
 */
public final class TeleportCommand {

    private static final String ROOT = "mdm";
    private static final String TELEPORT = "tp";

    private static final String ARG_TARGETS = "targets";
    private static final String ARG_DIMENSION = "dimension";
    private static final String ARG_POS = "pos";

    /**
     * 维度参数的补全。
     * <p>
     * {@code DimensionArgument} 自带"提示全部已加载维度"，这里换上自己的，好让
     * {@code Config.SHOW_OTHER_DIMENSIONS} 对命令行也生效。Brigadier 里自定义 provider 会顶掉
     * 参数类型自带的补全，但**不影响解析**：关掉之后照样能手打 {@code minecraft:the_nether} 传送过去，
     * 只是不再提示。
     */
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_VISIBLE_DIMENSIONS =
            (context, builder) -> {
                MinecraftServer server = context.getSource().getServer();
                return SharedSuggestionProvider.suggest(
                        DynamicDimensionManager.list(server).stream()
                                .filter(MdmDimensions::isVisible)
                                .map(key -> key.location().toString())
                                .sorted(), builder);
            };

    /**
     * 构建 {@code /mdm tp} 分支。
     * <p>
     * 返回的 builder 和 {@link DimensionCommand#get()} 同根，注册方两个都要
     * {@code dispatcher.register(...)}——Brigadier 会把同名字面量节点合并，两处子命令因此挂在
     * 同一棵 {@code /mdm} 树上。
     */
    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal(ROOT)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal(TELEPORT)
                        .then(Commands.argument(ARG_TARGETS, EntityArgument.players())
                                .then(Commands.argument(ARG_DIMENSION, DimensionArgument.dimension())
                                        .suggests(SUGGEST_VISIBLE_DIMENSIONS)
                                        .executes(context -> teleport(context, false))
                                        .then(Commands.argument(ARG_POS, BlockPosArgument.blockPos())
                                                .executes(context -> teleport(context, true))))));
    }

    private static int teleport(CommandContext<CommandSourceStack> context, boolean hasPos)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel target = DimensionArgument.getDimension(context, ARG_DIMENSION);
        Collection<ServerPlayer> players = EntityArgument.getPlayers(context, ARG_TARGETS);
        BlockPos pos = hasPos ? BlockPosArgument.getBlockPos(context, ARG_POS) : null;

        int moved = 0;
        for (ServerPlayer player : players) {
            if (DynamicDimensionManager.teleport(player, target.dimension(), pos)) {
                moved++;
            }
        }

        if (moved == 0) {
            source.sendFailure(Component.translatable(
                    "message.mdm.command.tp.failed", target.dimension().location()));
            return 0;
        }
        int count = moved;
        source.sendSuccess(() -> Component.translatable(
                "message.mdm.command.tp.success", count, target.dimension().location()), true);
        return moved;
    }
}
