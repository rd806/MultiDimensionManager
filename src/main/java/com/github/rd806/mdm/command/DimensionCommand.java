package com.github.rd806.mdm.command;

import com.github.rd806.mdm.MdmMain;
import com.github.rd806.mdm.dimension.data.DimensionImportSource;
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
 * {@code /mdm dimension ...} 命令组。
 * <pre>
 *   /mdm dimension list
 *   /mdm dimension create &lt;id&gt; &lt;模板&gt;
 *   /mdm dimension delete &lt;id&gt;
 *   /mdm dimension imports
 *   /mdm dimension import &lt;id&gt; &lt;源名&gt; [源维度] [模板]
 *   /mdm dimension spawn &lt;id&gt;               显示该维度的传送出生点
 *   /mdm dimension spawn &lt;id&gt; set [坐标]     设置（省略坐标时用执行者所在位置）
 *   /mdm dimension spawn &lt;id&gt; clear         清除
 * </pre>
 * <p>
 * 出生点只影响 {@code /mdm tp} 不给坐标时的落点，不是世界真正的出生点。
 * <p>
 * 传送命令本身在 {@link TeleportCommand}，它和这里同根（都是 {@code /mdm}）。
 */
public final class DimensionCommand {

    private static final String ROOT = "mdm";
    private static final String DIMENSION = "dimension";
    private static final String LIST = "list";
    private static final String CREATE = "create";
    private static final String IMPORT = "import";
    private static final String DELETE = "delete";

    private static final String ARG_ID = "id";
    private static final String ARG_TEMPLATE = "template";
    private static final String ARG_SOURCE = "source";
    private static final String ARG_SOURCE_DIMENSION = "sourceDimension";

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
     * 权限要求挂在根节点上（Brigadier 会把 requires 向下传递到所有子节点）。
     */
    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal(ROOT)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal(DIMENSION)
                        .then(listNode())
                        .then(createNode())
                        .then(importNode())
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

        if (!MdmDimensions.isSafePath(id)) {
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

        if (!MdmDimensions.isSafePath(id)) {
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
            // 把具体原因原样报出来，不要吞成笼统的「导入失败」
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
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
