package com.github.rd806.mdm.command;

import com.github.rd806.mdm.MdmMain;
import com.github.rd806.mdm.dimension.MdmDimensions;
import com.github.rd806.mdm.dimension.manager.DynamicDimensionManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

public final class ModifyCommand {

    private static final String ROOT = "mdm";
    private static final String MODIFY = "modify";

    private static final String ALIAS = "alias";
    private static final String SPAWN = "spawn";

    private static final String SET = "set";
    private static final String CLEAR = "clear";

    private static final String ARG_ID = "id";
    private static final String ARG_POS = "pos";
    private static final String ARG_ALIAS = "name";

    // 只提示本模组管理的维度，读 {@link MdmMain#LEVELS}。
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_MANAGED =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    MdmMain.LEVELS.stream().map(key -> key.location().toString()).sorted(), builder);


    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal(ROOT)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal(MODIFY)
                        .then(spawnNode())
                        .then(aliasNode())
                );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> spawnNode() {
        return Commands.literal(SPAWN)
                .then(Commands.argument(ARG_ID, ResourceLocationArgument.id())
                        .suggests(SUGGEST_MANAGED)
                        .executes(ModifyCommand::spawnGet)
                        .then(Commands.literal(SET)
                                .executes(context -> spawnSet(context, false))
                                .then(Commands.argument(ARG_POS, BlockPosArgument.blockPos())
                                        .executes(context -> spawnSet(context, true))))
                        .then(Commands.literal(CLEAR)
                                .executes(ModifyCommand::spawnClear)));
    }

    /**
     * {@code /mdm modify alias <id> [名字|clear]}。
     * <p>
     * 名字用 {@code greedyString}，整条剩余输入都算名字，于是别名里可以带空格。它和同一层的
     * {@code clear} 字面量理论上都能吃下 "clear" 这个词，但 Brigadier 的
     * {@code CommandNode#compareTo} 让字面量节点排在参数节点前面，所以 "clear" 稳定命中清除分支。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> aliasNode() {
        return Commands.literal(ALIAS)
                .then(Commands.argument(ARG_ID, ResourceLocationArgument.id())
                        .suggests(SUGGEST_MANAGED)
                        .executes(ModifyCommand::aliasGet)
                        .then(Commands.literal(CLEAR)
                                .executes(ModifyCommand::aliasClear))
                        .then(Commands.argument(ARG_ALIAS, StringArgumentType.greedyString())
                                .executes(ModifyCommand::aliasSet)));
    }

    // spawn

    private static int spawnGet(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);
        ResourceKey<Level> key = MdmDimensions.levelKey(id);

        // 先区分「不支持配置」和「支持但还没配」——否则对 vanilla 维度会报出误导性的提示
        if (!MdmMain.LEVELS.contains(key)) {
            source.sendFailure(Component.translatable("message.mdm.command.modify.spawn.not_managed", id));
            return 0;
        }

        BlockPos spawn = DynamicDimensionManager.spawnOf(server, key);
        if (spawn == null) {
            source.sendSuccess(() -> Component.translatable("message.mdm.command.modify.spawn.unset", id), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "message.mdm.command.modify.spawn.get", id, format(spawn)), false);
        return 1;
    }

    private static int spawnSet(CommandContext<CommandSourceStack> context, boolean hasPos) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);
        // 省略坐标时用执行者当前位置——管理员通常就站在想设的那个点上敲这条命令
        BlockPos pos = hasPos
                ? BlockPosArgument.getBlockPos(context, ARG_POS)
                : BlockPos.containing(source.getPosition());

        if (!DynamicDimensionManager.setSpawn(server, MdmDimensions.levelKey(id), pos)) {
            source.sendFailure(Component.translatable("message.mdm.command.modify.spawn.not_managed", id));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "message.mdm.command.modify.spawn.set", id, format(pos)), true);
        return 1;
    }

    private static int spawnClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);

        if (!DynamicDimensionManager.setSpawn(server, MdmDimensions.levelKey(id), null)) {
            source.sendFailure(Component.translatable("message.mdm.command.modify.spawn.not_managed", id));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "message.mdm.command.modify.spawn.clear", id), true);
        return 1;
    }

    // alias

    /** 别名长度上限：够长到能写清楚，又短到不会把 GUI 那一行撑爆（32 个全角字约 288px）。 */
    private static final int ALIAS_MAX_LENGTH = 32;

    private static int aliasGet(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);
        ResourceKey<Level> key = MdmDimensions.levelKey(id);

        // 先区分「不支持配置」和「支持但还没设」——否则对 vanilla 维度会报出误导性的提示
        if (!MdmMain.LEVELS.contains(key)) {
            source.sendFailure(Component.translatable("message.mdm.command.modify.alias.not_managed", id));
            return 0;
        }

        String alias = DynamicDimensionManager.aliasOf(server, key);
        if (alias == null) {
            source.sendSuccess(() -> Component.translatable("message.mdm.command.modify.alias.unset", id), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "message.mdm.command.modify.alias.get", id, alias), false);
        return 1;
    }

    private static int aliasSet(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);
        // 去掉首尾空白：别名是给人看的，用户多打一个空格不该存进去
        String alias = StringArgumentType.getString(context, ARG_ALIAS).trim();

        // 空名字当清除处理，同时兜住 /mdm modify alias <id> "" 这种输入
        if (alias.isEmpty()) {
            return aliasClear(context);
        }
        if (alias.length() > ALIAS_MAX_LENGTH) {
            source.sendFailure(Component.translatable(
                    "message.mdm.command.modify.alias.too_long", ALIAS_MAX_LENGTH));
            return 0;
        }

        if (!DynamicDimensionManager.setAlias(server, MdmDimensions.levelKey(id), alias)) {
            source.sendFailure(Component.translatable("message.mdm.command.modify.alias.not_managed", id));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "message.mdm.command.modify.alias.set", id, alias), true);
        return 1;
    }

    private static int aliasClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);

        if (!DynamicDimensionManager.setAlias(server, MdmDimensions.levelKey(id), null)) {
            source.sendFailure(Component.translatable("message.mdm.command.modify.alias.not_managed", id));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "message.mdm.command.modify.alias.clear", id), true);
        return 1;
    }

    /** BlockPos 在 1.20.1 没有 toShortString()，自己拼一个。 */
    private static String format(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
