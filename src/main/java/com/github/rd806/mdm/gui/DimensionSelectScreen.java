package com.github.rd806.mdm.gui;

import com.github.rd806.mdm.dimension.MdmDimensions;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 维度选择界面：一个浮在游戏画面上的窗口，里面是可滚动的维度列表，点一行就传送过去。
 * <p>
 * 刻意<b>不</b>调 {@code Screen#renderBackground}——那是整屏变暗（{@code fillGradient} 铺满 +
 * 模糊），本界面要的是一块居中浮窗，四周仍然看得见世界。面板自己画底和边框，列表自身的
 * 底纹（{@code AbstractSelectionList} 默认会铺一层 dirt 贴图）也关掉，否则会在浮窗里再糊一层灰。
 * <p>
 * 仍然是普通的 {@code Screen}：鼠标归界面管（不能边玩边点），ESC 关闭，滚轮和点击都是原版行为。
 * 窗口尺寸变化时 {@code Screen#resize} → {@code rebuildWidgets} → {@link #init()} 会重新算布局。
 * <p>
 * 纯客户端类，只被 {@link OpenDimensionSelectPacket} 的处理器通过
 * {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)} 触碰，专用服务端不会加载它。
 * <p>
 * 列表内容全部来自服务端下发的条目，界面自己不查询任何维度信息——唯一的例外是"当前维度"
 * 的判断，那用客户端自己的 {@code player.level().dimension()}，不需要服务端多说。
 */
public class DimensionSelectScreen extends Screen {

    /** 行高按两行算：别名一行、id 一行。没别名的行只有一行，会垂直居中。 */
    private static final int ROW_HEIGHT = 28;
    /** 第二行 id 的缩放：原版没有独立的小号字体，靠 pose 压小。 */
    private static final float ID_SCALE = 0.75F;

    // 浮窗尺寸。窗口太小时按可用空间缩，别画到屏幕外
    private static final int PANEL_MAX_WIDTH = 340;
    private static final int PANEL_MAX_HEIGHT = 260;
    private static final int PANEL_MIN_SIZE = 60;
    private static final int PANEL_MARGIN = 20;
    private static final int TITLE_BAR_HEIGHT = 18;
    /** 列表右缘到面板右缘的距离，留给滚动条。 */
    private static final int SCROLLBAR_ROOM = 14;

    private static final int COLOR_PANEL = 0xC0101010;
    private static final int COLOR_BORDER = 0xFFB0B0B0;
    private static final int COLOR_TITLE_SEPARATOR = 0x40FFFFFF;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_HOVER_TEXT = 0xFFFFA0;
    private static final int COLOR_CURRENT_TEXT = 0x707070;
    private static final int COLOR_KIND = 0x909090;
    private static final int COLOR_HOVER_BG = 0x33FFFFFF;
    private static final int COLOR_TITLE = 0xFFFFFF;

    private final List<OpenDimensionSelectPacket.Entry> entries;

    // 面板矩形，在 init() 里按当前窗口大小算好
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;

    private DimensionSelectScreen(List<OpenDimensionSelectPacket.Entry> entries) {
        super(Component.translatable("gui.mdm.dimension_select.title"));
        this.entries = entries;
    }

    /** 由 S2C 包处理器调用。 */
    public static void open(List<OpenDimensionSelectPacket.Entry> entries) {
        Minecraft.getInstance().setScreen(new DimensionSelectScreen(entries));
    }

    @Override
    protected void init() {
        // 窗口尺寸变化时 rebuildWidgets() 会清空控件再回来，所以这里整块重算，不做增量
        this.panelWidth = Math.max(PANEL_MIN_SIZE, Math.min(PANEL_MAX_WIDTH, this.width - PANEL_MARGIN));
        this.panelHeight = Math.max(PANEL_MIN_SIZE, Math.min(PANEL_MAX_HEIGHT, this.height - PANEL_MARGIN));
        this.panelLeft = (this.width - this.panelWidth) / 2;
        this.panelTop = (this.height - this.panelHeight) / 2;

        // 列表宽度给整个屏幕宽：行的水平位置是自己在自身宽度里居中的，而面板也居中，
        // 两者中心重合，于是行正好落在面板里。纵向才是面板里那块可见区域。
        DimensionList list = new DimensionList(this.minecraft, this.width, this.height,
                this.panelTop + TITLE_BAR_HEIGHT + 4, this.panelTop + this.panelHeight - 4);
        list.setRenderBackground(false);
        list.setRenderTopAndBottom(false);
        this.entries.forEach(entry -> list.addRow(new Row(entry)));
        this.addRenderableWidget(list);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 不调 renderBackground：那会把整屏压暗，浮窗要留着四周的世界
        graphics.fill(this.panelLeft, this.panelTop,
                this.panelLeft + this.panelWidth, this.panelTop + this.panelHeight, COLOR_PANEL);
        graphics.renderOutline(this.panelLeft, this.panelTop, this.panelWidth, this.panelHeight, COLOR_BORDER);

        super.render(graphics, mouseX, mouseY, partialTick);

        // 标题栏压在列表之上画，顺带把标题和列表分开
        graphics.drawCenteredString(this.font, this.title,
                this.panelLeft + this.panelWidth / 2, this.panelTop + 5, COLOR_TITLE);
        graphics.hLine(this.panelLeft + 2, this.panelLeft + this.panelWidth - 3,
                this.panelTop + TITLE_BAR_HEIGHT - 1, COLOR_TITLE_SEPARATOR);
    }

    /**
     * 单人存档里打开界面时不要让游戏暂停。
     * <p>
     * 点一下就要发传送包，暂停会让整个流程卡在一个停住的世界上，没必要。
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 可滚动的维度列表。 */
    private class DimensionList extends ObjectSelectionList<Row> {

        DimensionList(Minecraft minecraft, int width, int height, int y0, int y1) {
            super(minecraft, width, height, y0, y1, ROW_HEIGHT);
        }

        /** {@code AbstractSelectionList#addEntry} 是 protected，只有子类能调，所以包一层。 */
        void addRow(Row row) {
            this.addEntry(row);
        }

        @Override
        public int getRowWidth() {
            // 行的水平位置是"以自身宽度居中"，所以行宽 = 面板宽 - 两侧留白 - 滚动条位置，
            // 否则行右缘会顶到滚动条上
            return Math.max(PANEL_MIN_SIZE, panelWidth - SCROLLBAR_ROOM - 12);
        }

        /** 默认值（width/2 + 124）会跑到面板外面去，贴着面板右缘内侧放。 */
        @Override
        protected int getScrollbarPosition() {
            return panelLeft + panelWidth - SCROLLBAR_ROOM + 4;
        }
    }

    /** 一行 = 一个维度。整行可点。 */
    private class Row extends ObjectSelectionList.Entry<Row> {

        private final OpenDimensionSelectPacket.Entry entry;

        Row(OpenDimensionSelectPacket.Entry entry) {
            this.entry = entry;
        }

        /**
         * 玩家是不是已经在这个维度里了。
         * <p>
         * 是的话这一行不可点：不给坐标的传送会把玩家挪到出生点/地表，站在原地被"传送"一下
         * 很反直觉。用客户端自己的当前维度判断，不需要服务端下发扬标记。
         */
        private boolean isCurrent() {
            if (minecraft == null || minecraft.player == null) {
                return false;
            }
            ResourceKey<Level> current = minecraft.player.level().dimension();
            return current.equals(MdmDimensions.levelKey(this.entry.id()));
        }

        @Override
        public void render(@NotNull GuiGraphics graphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
            boolean current = this.isCurrent();
            if (isMouseOver && !current) {
                graphics.fill(left - 1, top - 1, left + width + 1, top + ROW_HEIGHT - 1, COLOR_HOVER_BG);
            }

            String id = this.entry.id().toString();
            String alias = this.entry.alias();
            boolean hasAlias = alias != null && !alias.isBlank();
            int primaryColor = current ? COLOR_CURRENT_TEXT : (isMouseOver ? COLOR_HOVER_TEXT : COLOR_TEXT);

            Component kind = Component.translatable(this.entry.isStatic()
                    ? "message.mdm.command.dimension.kind.static"
                    : "message.mdm.command.dimension.kind.dynamic");

            if (hasAlias) {
                // 两行：别名当主标题，id 当副标题
                int primaryY = top + 3;
                graphics.drawString(font, alias, left + 4, primaryY, primaryColor, false);
                graphics.drawString(font, kind, left + 8 + font.width(alias), primaryY, COLOR_KIND, false);

                PoseStack pose = graphics.pose();
                pose.pushPose();
                pose.translate(left + 4, top + 15, 0);
                pose.scale(ID_SCALE, ID_SCALE, 1.0F);
                graphics.drawString(font, id, 0, 0, COLOR_KIND, false);
                pose.popPose();
            } else {
                // 没别名就只画 id，单行垂直居中，否则这些行会平白显得偏上
                int y = top + (height - font.lineHeight) / 2;
                graphics.drawString(font, id, left + 4, y, primaryColor, false);
                graphics.drawString(font, kind, left + 8 + font.width(id), y, COLOR_KIND, false);
            }

            if (current) {
                // (当前) 挂在主标题那一行的右端
                Component tag = Component.translatable("gui.mdm.dimension_select.current");
                graphics.drawString(font, tag, left + width - font.width(tag) - 4,
                        hasAlias ? top + 3 : top + (height - font.lineHeight) / 2, COLOR_KIND, false);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0 || this.isCurrent()) {
                return false;
            }
            // 先发包再关界面：关掉之后这次点击不会再触发第二次
            MdmGuiNetwork.requestTeleport(this.entry.id());
            if (minecraft != null) {
                minecraft.setScreen(null);
            }
            return true;
        }

        @Override
        public @NotNull Component getNarration() {
            return Component.literal(this.entry.id().toString());
        }
    }
}
