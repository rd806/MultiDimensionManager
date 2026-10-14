package com.github.rd806.mdm.command;

import com.github.rd806.mdm.MdmMain;
import com.github.rd806.mdm.dimension.data.DimensionImportSource;
import com.github.rd806.mdm.dimension.data.DimensionSavedData;
import com.github.rd806.mdm.dimension.data.DimensionTemplates;
import com.github.rd806.mdm.dimension.manager.DynamicDimensionManager;
import com.github.rd806.mdm.dimension.MdmDimensions;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /mdm dimension ...} 命令组，管理维度的增删和重置。
 * <pre>
 *   /mdm dimension list
 *   /mdm dimension create &lt;id&gt; &lt;模板&gt;
 *   /mdm dimension delete &lt;id&gt;
 *   /mdm dimension import &lt;id&gt; &lt;源名&gt; [源维度] [模板]
 *   /mdm dimension reset &lt;id&gt; [confirm]   按备份原地重导地形
 * </pre>
 */
public final class DimensionCommand {

    private static final String ROOT = "mdm";
    private static final String DIMENSION = "dimension";
    private static final String LIST = "list";
    private static final String CREATE = "create";
    private static final String IMPORT = "import";
    private static final String RESET = "reset";
    private static final String DELETE = "delete";

    private static final String ARG_ID = "id";
    private static final String ARG_TEMPLATE = "template";
    private static final String ARG_SOURCE = "source";
    private static final String ARG_SOURCE_DIMENSION = "sourceDimension";
    private static final String CONFIRM = "confirm";

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TEMPLATES =
            (context, builder) -> SharedSuggestionProvider.suggest(DimensionTemplates.names(), builder);

    /**
     * 只提示本模组管理的维度，读 {@link MdmMain#LEVELS}。
     * <p>
     * 用在 {@code delete} 这种只对动态维度有意义的参数上——静态 JSON 维度和 vanilla 的三个维度
     * 都不该出现在这里。
     */
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_MANAGED =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    MdmMain.LEVELS.stream().map(key -> key.location().toString()).sorted(), builder);

    /**
     * 构建整棵 {@code /mdm} 命令树。
     * <p>
     * 暴露成返回 builder 的方法而不是直接在 dispatcher 上注册：这棵树可以被单独构造和检查，
     * 也保证了整棵树只有这一处定义。注册方调用
     * {@code dispatcher.register(DimensionCommand.get())} 即可。
     * <p>
     * <b>权限要求挂在子命令字面量上，不能挂根节点</b>：四个命令类注册的是同一棵 {@code /mdm} 树，
     * Brigadier 合并同名节点时只保留第一个注册的 requirement（详见
     * {@code ServerSetup#onRegisterCommands}），挂在根上的谓词会盖住所有子命令，包括本该按
     * 维度白名单放行的 {@code gui}。
     */
    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal(ROOT)
                .then(Commands.literal(DIMENSION)
                        .requires(source -> source.hasPermission(2))
                        .then(listNode())
                        .then(createNode())
                        .then(importNode())
                        .then(resetNode())
                        .then(deleteNode())
                );
    }

    private static LiteralArgumentBuilder<CommandSourceStack> listNode() {
        return Commands.literal(LIST).executes(DimensionCommand::list);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createNode() {
        return Commands.literal(CREATE)
                .then(Commands.argument(ARG_ID, ResourceLocationArgument.id())
                        .then(Commands.argument(ARG_TEMPLATE, StringArgumentType.word())
                                .suggests(SUGGEST_TEMPLATES)
                                .executes(DimensionCommand::create)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> importNode() {
        return Commands.literal(IMPORT)
                .then(Commands.argument(ARG_ID, ResourceLocationArgument.id())
                        .then(Commands.argument(ARG_SOURCE, StringArgumentType.word())
                                .suggests(DimensionCommand::suggestSources)
                                .executes(context -> importDimension(context, false, false))
                                .then(Commands.argument(ARG_SOURCE_DIMENSION, ResourceLocationArgument.id())
                                        .executes(context -> importDimension(context, true, false))
                                        .then(Commands.argument(ARG_TEMPLATE, StringArgumentType.word())
                                                .suggests(SUGGEST_TEMPLATES)
                                                .executes(context -> importDimension(context, true, true))))));
    }

    /**
     * {@code /mdm dimension reset <id> [confirm]}——照导入时记下的配方，把这个维度的地形重导一遍。
     * <p>
     * 不带 {@code confirm} 只预览。这条命令会把该维度的 region 文件整个覆盖掉且不可恢复，
     * 敲错一个 id 的代价太大，值得多敲一个词。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> resetNode() {
        return Commands.literal(RESET)
                .then(Commands.argument(ARG_ID, ResourceLocationArgument.id())
                        .suggests(SUGGEST_MANAGED)
                        .executes(DimensionCommand::resetPreview)
                        .then(Commands.literal(CONFIRM)
                                .executes(DimensionCommand::resetConfirmed)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> deleteNode() {
        return Commands.literal(DELETE)
                .then(Commands.argument(ARG_ID, ResourceLocationArgument.id())
                        .suggests(SUGGEST_MANAGED)
                        .executes(DimensionCommand::delete));
    }

    // list 命令
    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        List<ResourceKey<Level>> keys = DynamicDimensionManager.list(server).stream()
                .filter(MdmDimensions::isVisible)
                .sorted(Comparator.comparing(key -> key.location().toString()))
                .toList();

        source.sendSuccess(() -> Component.translatable(
                "message.mdm.command.dimension.list.header", keys.size()), false);
        for (ResourceKey<Level> key : keys) {
            Component kind = Component.translatable(MdmDimensions.isStatic(server, key)
                    ? "message.mdm.command.dimension.kind.static"
                    : "message.mdm.command.dimension.kind.dynamic");
            source.sendSuccess(() -> Component.translatable(
                    "message.mdm.command.dimension.list.entry", key.location(), kind), false);
        }
        return keys.size();
    }

    // create 命令
    private static int create(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);
        String template = StringArgumentType.getString(context, ARG_TEMPLATE);

        if (!DimensionTemplates.exists(template)) {
            source.sendFailure(Component.translatable(
                    "message.mdm.command.dimension.create.unknown_template",
                    template, String.join(", ", DimensionTemplates.names())));
            return 0;
        }

        if (MdmDimensions.isUnSafePath(id)) {
            source.sendFailure(Component.translatable("message.mdm.command.dimension.unsafe_id", id));
            return 0;
        }

        ResourceKey<Level> key = MdmDimensions.levelKey(id);
        if (server.getLevel(key) != null) {
            source.sendFailure(Component.translatable("message.mdm.command.dimension.exists", id));
            return 0;
        }

        LevelStem stem = DimensionTemplates.build(server, template);
        if (stem == null) {
            source.sendFailure(Component.translatable(
                    "message.mdm.command.dimension.create.build_failed", template));
            return 0;
        }

        DynamicDimensionManager.createAndPersist(server, key, stem);
        source.sendSuccess(() -> Component.translatable(
                "message.mdm.command.dimension.create.success", id, template), true);
        return 1;
    }

    // import 命令
    private static CompletableFuture<Suggestions> suggestSources(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(DimensionImportSource.list(), builder);
    }

    private static int importDimension(CommandContext<CommandSourceStack> context,
                                       boolean hasSourceDim, boolean hasTemplate) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);
        String sourceName = StringArgumentType.getString(context, ARG_SOURCE);
        ResourceLocation sourceDim = hasSourceDim
                ? ResourceLocationArgument.getId(context, ARG_SOURCE_DIMENSION)
                : Level.OVERWORLD.location();
        String template = hasTemplate ? StringArgumentType.getString(context, ARG_TEMPLATE) : null;

        if (MdmDimensions.isUnSafePath(id)) {
            source.sendFailure(Component.translatable("message.mdm.command.dimension.unsafe_id", id));
            return 0;
        }

        ResourceKey<Level> key = MdmDimensions.levelKey(id);
        if (server.getLevel(key) != null) {
            source.sendFailure(Component.translatable("message.mdm.command.dimension.exists", id));
            return 0;
        }

        try {
            DimensionImportSource importSource = DimensionImportSource.locate(sourceName);
            DynamicDimensionManager.ImportResult result =
                    DynamicDimensionManager.importDimension(server, key, importSource, sourceDim, template);
            source.sendSuccess(() -> Component.translatable(
                    "message.mdm.command.dimension.import.success",
                    result.chunkFiles(), result.source(), sourceDim, result.dimensionId()), true);
            return 1;
        } catch (DimensionImportSource.ImportException e) {
            // 把具体原因原样报出来，不要吞成笼统的「导入失败」。异常里带的就是 translatable，
            // 直接投递即可，客户端按玩家自己的语言渲染
            source.sendFailure(e.component());
            return 0;
        }
    }

    // reset 命令

    /** 只做预览，不动数据。 */
    private static int resetPreview(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);

        DimensionSavedData.Backup backup =
                DynamicDimensionManager.backupOf(server, MdmDimensions.levelKey(id));
        if (backup == null) {
            source.sendFailure(Component.translatable("message.mdm.import.no_backup", id));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("message.mdm.command.dimension.reset.preview",
                id, backup.source(), backup.sourceDim(), backupTemplateName(backup)), false);
        return 1;
    }

    private static int resetConfirmed(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);

        try {
            DynamicDimensionManager.ImportResult result =
                    DynamicDimensionManager.resetFromBackup(server, MdmDimensions.levelKey(id));
            source.sendSuccess(() -> Component.translatable("message.mdm.command.dimension.reset.success",
                    id, result.source(), result.chunkFiles()), true);
            return 1;
        } catch (DimensionImportSource.ImportException e) {
            // 和 import 一样把原因原样报出来（异常里带的就是 translatable），别吞成笼统的一句
            source.sendFailure(e.component());
            return 0;
        }
    }

    /** 备份里没记模板时显示成一句说明，而不是留白。 */
    private static Component backupTemplateName(DimensionSavedData.Backup backup) {
        return backup.template() == null
                ? Component.translatable("message.mdm.command.modify.backup.no_template")
                : Component.literal(backup.template());
    }

    // delete 命令

    private static int delete(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);
        ResourceKey<Level> key = MdmDimensions.levelKey(id);

        if (server.getLevel(key) == null) {
            source.sendFailure(Component.translatable("message.mdm.command.dimension.delete.not_loaded", id));
            return 0;
        }
        if (MdmDimensions.isStatic(server, key)) {
            source.sendFailure(Component.translatable("message.mdm.command.dimension.delete.static", id));
            return 0;
        }
        if (!DynamicDimensionManager.delete(server, key)) {
            source.sendFailure(Component.translatable("message.mdm.command.dimension.delete.failed", id));
            return 0;
        }

        source.sendSuccess(() -> Component.translatable(
                "message.mdm.command.dimension.delete.success", id), true);
        return 1;
    }
}
