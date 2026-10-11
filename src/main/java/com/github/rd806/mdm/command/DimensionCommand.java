package com.github.rd806.mdm.command;

import com.github.rd806.mdm.MdmMain;
import com.github.rd806.mdm.dimension.data.DimensionImportSource;
import com.github.rd806.mdm.dimension.data.DimensionTemplates;
import com.github.rd806.mdm.dimension.manager.DynamicDimensionManager;
import com.github.rd806.mdm.dimension.MdmDimensions;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /mdm dimension ...} 命令组。
 * <pre>
 *   /mdm dimension list
 *   /mdm dimension create &lt;id&gt; &lt;模板&gt;
 *   /mdm dimension delete &lt;id&gt;
 *   /mdm dimension tp &lt;目标&gt; &lt;维度&gt; [坐标]
 *   /mdm dimension imports
 *   /mdm dimension import &lt;id&gt; &lt;源名&gt; [源维度] [模板]
 *   /mdm dimension spawn &lt;id&gt;               显示该维度的传送出生点
 *   /mdm dimension spawn &lt;id&gt; set [坐标]     设置（省略坐标时用执行者所在位置）
 *   /mdm dimension spawn &lt;id&gt; clear         清除
 * </pre>
 * <p>
 * 出生点只影响 {@code tp} 不给坐标时的落点，不是世界真正的出生点。
 */
public final class DimensionCommand {

    private static final String ROOT = "mdm";
    private static final String DIMENSION = "dimension";
    private static final String LIST = "list";
    private static final String CREATE = "create";
    private static final String IMPORT = "import";
    private static final String IMPORTS = "imports";
    private static final String DELETE = "delete";
    private static final String TELEPORT = "tp";
    private static final String SPAWN = "spawn";
    private static final String SET = "set";
    private static final String CLEAR = "clear";

    private static final String ARG_ID = "id";
    private static final String ARG_TEMPLATE = "template";
    private static final String ARG_SOURCE = "source";
    private static final String ARG_SOURCE_DIMENSION = "sourceDimension";
    private static final String ARG_TARGETS = "targets";
    private static final String ARG_DIMENSION = "dimension";
    private static final String ARG_POS = "pos";

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_TEMPLATES =
            (context, builder) -> SharedSuggestionProvider.suggest(DimensionTemplates.names(), builder);

    /**
     * 只提示本模组管理的维度，读 {@link MdmMain#LEVELS}。
     * <p>
     * 用在 {@code delete} 这种只对动态维度有意义的参数上——静态 JSON 维度和 vanilla 的三个维度
     * 都不该出现在这里。{@code tp} 故意不用它：那里走 {@code DimensionArgument}，
     * 会提示全部已加载维度（含主世界/下界/末地），换成这个反而是退步。
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
                .then(dimension());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> dimension() {
        return Commands.literal(DIMENSION)
                .then(listNode())
                .then(createNode())
                .then(importsNode())
                .then(importNode())
                .then(deleteNode())
                .then(spawnNode())
                .then(teleportNode());
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

    private static LiteralArgumentBuilder<CommandSourceStack> importsNode() {
        return Commands.literal(IMPORTS).executes(DimensionCommand::imports);
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

    private static LiteralArgumentBuilder<CommandSourceStack> spawnNode() {
        return Commands.literal(SPAWN)
                .then(Commands.argument(ARG_ID, ResourceLocationArgument.id())
                        .suggests(SUGGEST_MANAGED)
                        .executes(DimensionCommand::spawnGet)
                        .then(Commands.literal(SET)
                                .executes(context -> spawnSet(context, false))
                                .then(Commands.argument(ARG_POS, BlockPosArgument.blockPos())
                                        .executes(context -> spawnSet(context, true))))
                        .then(Commands.literal(CLEAR)
                                .executes(DimensionCommand::spawnClear)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> teleportNode() {
        return Commands.literal(TELEPORT)
                .then(Commands.argument(ARG_TARGETS, EntityArgument.players())
                        .then(Commands.argument(ARG_DIMENSION, DimensionArgument.dimension())
                                .executes(context -> teleport(context, false))
                                .then(Commands.argument(ARG_POS, BlockPosArgument.blockPos())
                                        .executes(context -> teleport(context, true)))));
    }

    // list 命令
    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();

        List<ResourceKey<Level>> keys = DynamicDimensionManager.list(server).stream()
                .sorted(Comparator.comparing(key -> key.location().toString()))
                .toList();

        source.sendSuccess(() -> Component.literal("Dimensions (" + keys.size() + "):"), false);
        for (ResourceKey<Level> key : keys) {
            String kind = MdmDimensions.isStatic(server, key) ? "static" : "dynamic";
            source.sendSuccess(() -> Component.literal(" - " + key.location() + " [" + kind + "]"), false);
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
            source.sendFailure(Component.literal(
                    "Unknown template '" + template + "'. Available: " + String.join(", ", DimensionTemplates.names())));
            return 0;
        }

        if (!MdmDimensions.isSafePath(id)) {
            source.sendFailure(Component.literal("非法的维度 id '" + id
                    + "'：path 不能是 \".\" 或 \"..\"，也不能含路径分隔符。"));
            return 0;
        }

        ResourceKey<Level> key = MdmDimensions.levelKey(id);
        if (server.getLevel(key) != null) {
            source.sendFailure(Component.literal("Dimension " + id + " already exists."));
            return 0;
        }

        LevelStem stem = DimensionTemplates.build(server, template);
        if (stem == null) {
            source.sendFailure(Component.literal(
                    "Could not build a LevelStem from template '" + template + "'. See the server log."));
            return 0;
        }

        DynamicDimensionManager.createAndPersist(server, key, stem);
        source.sendSuccess(() -> Component.literal(
                "Created dimension " + id + " from template '" + template + "'."), true);
        return 1;
    }

    // import 命令
    private static int imports(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> names = DimensionImportSource.list();

        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.literal("没有可导入的源。把一个存档文件夹放进 "
                    + DimensionImportSource.directory() + "/ 后重试。"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "可导入的源（" + DimensionImportSource.directory() + "）："), false);
        for (String name : names) {
            source.sendSuccess(() -> Component.literal(" - " + name), false);
        }
        return names.size();
    }

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
            source.sendFailure(Component.literal("非法的维度 id '" + id
                    + "'：path 不能是 \".\" 或 \"..\"，也不能含路径分隔符。"));
            return 0;
        }

        ResourceKey<Level> key = MdmDimensions.levelKey(id);
        if (server.getLevel(key) != null) {
            source.sendFailure(Component.literal("Dimension " + id + " already exists."));
            return 0;
        }

        try {
            DimensionImportSource importSource = DimensionImportSource.locate(sourceName);
            DynamicDimensionManager.ImportResult result =
                    DynamicDimensionManager.importDimension(server, key, importSource, sourceDim, template);
            source.sendSuccess(() -> Component.literal("已从 '" + result.source() + "'（" + sourceDim
                    + "）导入 " + result.chunkFiles() + " 个区块文件到 " + result.dimensionId() + "。"), true);
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
            source.sendFailure(Component.literal("Dimension " + id + " is not loaded."));
            return 0;
        }
        if (MdmDimensions.isStatic(server, key)) {
            source.sendFailure(Component.literal("Dimension " + id
                    + " is defined by a datapack and cannot be deleted at runtime."));
            return 0;
        }
        if (!DynamicDimensionManager.delete(server, key)) {
            source.sendFailure(Component.literal("Failed to delete dimension " + id + "."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Deleted dimension " + id + "."), true);
        return 1;
    }

    // tp 命令

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
            source.sendFailure(Component.literal("Failed to teleport to " + target.dimension().location() + "."));
            return 0;
        }
        int count = moved;
        source.sendSuccess(() -> Component.literal("Teleported " + count + " player(s) to "
                + target.dimension().location() + "."), true);
        return moved;
    }

    // ---------------------------------------------------------------- spawn

    private static int spawnGet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);
        ResourceKey<Level> key = MdmDimensions.levelKey(id);

        // 先区分「不支持配置」和「支持但还没配」——否则对 vanilla 维度会报出误导性的提示
        if (!MdmMain.LEVELS.contains(key)) {
            source.sendFailure(Component.literal(id + " 不由本模组管理，无法配置传送出生点。"));
            return 0;
        }

        BlockPos spawn = DynamicDimensionManager.spawnOf(server, key);
        if (spawn == null) {
            source.sendSuccess(() -> Component.literal(id
                    + " 还没有配置传送出生点；tp 不给坐标时会退到地表计算。"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(id + " 的传送出生点：" + format(spawn)), false);
        return 1;
    }

    private static int spawnSet(CommandContext<CommandSourceStack> context, boolean hasPos)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);
        // 省略坐标时用执行者当前位置——管理员通常就站在想设的那个点上敲这条命令
        BlockPos pos = hasPos
                ? BlockPosArgument.getBlockPos(context, ARG_POS)
                : BlockPos.containing(source.getPosition());

        if (!DynamicDimensionManager.setSpawn(server, MdmDimensions.levelKey(id), pos)) {
            source.sendFailure(Component.literal(id + " 不由本模组管理，无法配置传送出生点。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已把 " + id + " 的传送出生点设为 " + format(pos) + "。"), true);
        return 1;
    }

    private static int spawnClear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ResourceLocation id = ResourceLocationArgument.getId(context, ARG_ID);

        if (!DynamicDimensionManager.setSpawn(server, MdmDimensions.levelKey(id), null)) {
            source.sendFailure(Component.literal(id + " 不由本模组管理，无法配置传送出生点。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已清除 " + id + " 的传送出生点。"), true);
        return 1;
    }

    /** BlockPos 在 1.20.1 没有 toShortString()，自己拼一个。 */
    private static String format(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
