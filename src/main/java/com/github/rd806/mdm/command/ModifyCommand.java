package com.github.rd806.mdm.command;

import com.github.rd806.mdm.dimension.MdmDimensions;
import com.github.rd806.mdm.dimension.data.DimensionImportSource;
import com.github.rd806.mdm.dimension.data.DimensionSavedData;
import com.github.rd806.mdm.dimension.data.DimensionTemplates;
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
    private static final String BACKUP = "backup";

    private static final String SET = "set";
    private static final String CLEAR = "clear";

    private static final String ARG_ID = "id";
    private static final String ARG_POS = "pos";
    private static final String ARG_ALIAS = "name";
    /** 备份的源目录名。和 import 命令的 source 参数同名同义——备份记的就是那次导入的配方。 */
    private static final String ARG_BACKUP_SOURCE = "source";
    private static final String ARG_SOURCE_DIMENSION = "sourceDimension";
    private static final String ARG_TEMPLATE = "template";

    /**
     * 提示所有可见的已加载维度，和 {@code /mdm tp} 的补全取同一个集合。
     * <p>
     * 不再只提示受管维度：spawn 和 alias 现在对任何已加载维度都能设。集合要不要被
     * {@code SHOW_OTHER_DIMENSIONS} 裁掉也交给 {@link MdmDimensions#isVisible}——
     * 那只是显示开关，裁掉之后手打 id 依然设得上。
     */
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_VISIBLE =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    DynamicDimensionManager.list(context.getSource().getServer()).stream()
                            .filter(MdmDimensions::isVisible)
                            .map(key -> key.location().toString())
                            .sorted(), builder);

    /** 导入目录下可选的源存档名，和 {@code /mdm dimension import} 的补全同源。 */
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_SOURCES =
            (context, builder) -> SharedSuggestionProvider.suggest(DimensionImportSource.list(), builder);

    /** 导入目录下可选的维度模版 */
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TEMPLATES =
            (context, builder) -> SharedSuggestionProvider.suggest(DimensionTemplates.names(), builder);


    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        // 权限挂子命令字面量、不挂根节点，理由见 ServerSetup#onRegisterCommands
        return Commands.literal(ROOT)
                .then(Commands.literal(MODIFY)
                        .requires(source -> source.hasPermission(2))
                        .then(spawnNode())
                        .then(aliasNode())
                        .then(backupNode())
                );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> spawnNode() {
        return Commands.literal(SPAWN)
                .then(Commands.argument(ARG_ID, ResourceLocationArgument.id())
                        .suggests(SUGGEST_VISIBLE)
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
                        .suggests(SUGGEST_VISIBLE)
                        .executes(ModifyCommand::aliasGet)
                        .then(Commands.literal(CLEAR)
                                .executes(ModifyCommand::aliasClear))
                        .then(Commands.argument(ARG_ALIAS, StringArgumentType.greedyString())
                                .executes(ModifyCommand::aliasSet)));
    }

    /**
     * {@code /mdm modify backup <id> [<源名> [源维度] [模板]] | clear}。
     * <p>
     * 参数形状刻意和 {@code /mdm dimension import <id> <源> [源维度] [模板]} 对齐——备份记的就是那次
     * 导入的配方，填法自然也该一样。省略的两项按导入命令的默认走（overworld / 无模板）。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> backupNode() {
        return Commands.literal(BACKUP)
                .then(Commands.argument(ARG_ID, ResourceLocationArgument.id())
                        .suggests(SUGGEST_VISIBLE)
                        .executes(ModifyCommand::backupGet)
                        .then(Commands.literal(CLEAR)
                                .executes(ModifyCommand::backupClear))
                        .then(Commands.argument(ARG_BACKUP_SOURCE, StringArgumentType.word())
                                .suggests(SUGGEST_SOURCES)
                                .executes(context -> backupSet(context, false, false))
                                .then(Commands.argument(ARG_SOURCE_DIMENSION, ResourceLocationArgument.id())
                                        .executes(context -> backupSet(context, true, false))
                                        .then(Commands.argument(ARG_TEMPLATE, StringArgumentType.word())
                                                .suggests(SUGGEST_TEMPLATES)
                                                .executes(context -> backupSet(context, true, true))))));
    }

    // spawn

    private static int spawnGet(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);
        ResourceKey<Level> key = MdmDimensions.levelKey(id);

        // 先区分「维度没加载」和「加载了但还没配」——否则后者会被第一条挡住，报成误导性的提示
        if (server.getLevel(key) == null) {
            source.sendFailure(Component.translatable("message.mdm.command.modify.spawn.not_loaded", id));
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
            source.sendFailure(Component.translatable("message.mdm.command.modify.spawn.not_loaded", id));
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
            source.sendFailure(Component.translatable("message.mdm.command.modify.spawn.not_loaded", id));
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

        // 先区分「维度没加载」和「加载了但还没设」——否则后者会被第一条挡住，报成误导性的提示
        if (server.getLevel(key) == null) {
            source.sendFailure(Component.translatable("message.mdm.command.modify.alias.not_loaded", id));
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
            source.sendFailure(Component.translatable("message.mdm.command.modify.alias.too_long", ALIAS_MAX_LENGTH));
            return 0;
        }

        if (!DynamicDimensionManager.setAlias(server, MdmDimensions.levelKey(id), alias)) {
            source.sendFailure(Component.translatable("message.mdm.command.modify.alias.not_loaded", id));
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
            source.sendFailure(Component.translatable("message.mdm.command.modify.alias.not_loaded", id));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "message.mdm.command.modify.alias.clear", id), true);
        return 1;
    }

    // backup

    private static int backupGet(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);
        ResourceKey<Level> key = MdmDimensions.levelKey(id);

        if (server.getLevel(key) == null) {
            source.sendFailure(Component.translatable("message.mdm.command.modify.backup.not_loaded", id));
            return 0;
        }

        DimensionSavedData.Backup backup = DynamicDimensionManager.backupOf(server, key);
        if (backup == null) {
            source.sendSuccess(() -> Component.translatable("message.mdm.command.modify.backup.unset", id), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("message.mdm.command.modify.backup.get",
                id, backup.source(), backup.sourceDim(), backupTemplateName(backup)), false);
        return 1;
    }

    private static int backupSet(CommandContext<CommandSourceStack> context, boolean hasSourceDim, boolean hasTemplate) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);
        String sourceName = StringArgumentType.getString(context, ARG_BACKUP_SOURCE);
        // 和 import 命令同一套默认值：省略源维度按主世界，省略模板就是不用模板
        ResourceLocation sourceDim = hasSourceDim
                ? ResourceLocationArgument.getId(context, ARG_SOURCE_DIMENSION)
                : Level.OVERWORLD.location();
        String template = hasTemplate ? StringArgumentType.getString(context, ARG_TEMPLATE) : null;

        // 先确认源真的在。存一条指向不存在目录的配方没有意义，当场报比等到重置时才炸出来好
        try {
            DimensionImportSource.locate(sourceName);
        } catch (DimensionImportSource.ImportException e) {
            source.sendFailure(e.component());
            return 0;
        }
        if (template != null && !DimensionTemplates.exists(template)) {
            source.sendFailure(Component.translatable(
                    "message.mdm.command.dimension.create.unknown_template",
                    template, String.join(", ", DimensionTemplates.names())));
            return 0;
        }

        if (!DynamicDimensionManager.setBackup(server, MdmDimensions.levelKey(id),
                new DimensionSavedData.Backup(sourceName, sourceDim, template))) {
            source.sendFailure(Component.translatable("message.mdm.command.modify.backup.not_loaded", id));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "message.mdm.command.modify.backup.set", id, sourceName), true);
        return 1;
    }

    private static int backupClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);

        if (!DynamicDimensionManager.setBackup(server, MdmDimensions.levelKey(id), null)) {
            source.sendFailure(Component.translatable("message.mdm.command.modify.backup.not_loaded", id));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "message.mdm.command.modify.backup.clear", id), true);
        return 1;
    }

    /** 备份里没记模板时显示成一句说明，而不是留白。 */
    static Component backupTemplateName(DimensionSavedData.Backup backup) {
        return backup.template() == null
                ? Component.translatable("message.mdm.command.modify.backup.no_template")
                : Component.literal(backup.template());
    }

    /** BlockPos 在 1.20.1 没有 toShortString()，自己拼一个。 */
    private static String format(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
