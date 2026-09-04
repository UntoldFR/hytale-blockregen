package com.nopefr.blockregen;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Custom UI window opened by "/blockregen list" (player-only). Shows every
 * configured block with its icon, an editable delay (NumberField) and a
 * S/M/H unit toggle, plus a remove button - all editable directly from the
 * window. Also lets the player add a new rule for any block in the game
 * (vanilla or modded) picked from a searchable dropdown, without needing to
 * target it in-game.
 */
public class BlockRegenListPage extends InteractiveCustomUIPage<BlockRegenListPage.ListPageEventData> {

    private static final String PAGE_FILE = "Pages/BlockRegen/BlockRegenListPage.ui";
    private static final String ROW_FILE = "Pages/BlockRegen/BlockRegenEntryRow.ui";

    private static final String UNIT_SECONDS = "S";
    private static final String UNIT_MINUTES = "M";
    private static final String UNIT_HOURS = "H";

    // Special "scope" dropdown entry meaning "edit the global rules" (as opposed to a CustomAreas
    // area name). Never a valid area name since CustomAreas area names come from user input there.
    private static final String SCOPE_GLOBAL = "Global";

    private final BlockRegenPlugin plugin;

    // blockId -> unit currently displayed for that row in this window session (not persisted).
    private final Map<String, String> displayUnitByBlockId = new HashMap<>();

    // Unit currently selected in the "add a new rule" panel (not persisted).
    private String addUnit = UNIT_MINUTES;

    // "Need floor" state currently selected in the "add a new rule" panel (not persisted).
    private boolean addNeedFloor = false;

    // "Regrowth" state currently selected in the "add a new rule" panel (not persisted).
    private boolean addRegrowth = false;

    // Currently selected scope: null = Global, otherwise a CustomAreas area name. Drives which
    // rules buildList()/addRule() read and write. Not persisted - resets to Global each time the
    // window is (re)opened.
    private String selectedScope = null;

    public BlockRegenListPage(@Nonnull PlayerRef playerRef, @Nonnull BlockRegenPlugin plugin) {
        super(playerRef, CustomPageLifetime.CanDismiss, ListPageEventData.CODEC);
        this.plugin = plugin;
    }

    @Override
    public void build(
        @Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder, @Nonnull Store<EntityStore> store
    ) {
        commandBuilder.append(PAGE_FILE);
        buildScopeSelector(commandBuilder, eventBuilder);
        buildAddSection(commandBuilder, eventBuilder);
        buildList(commandBuilder, eventBuilder);
    }

    /**
     * Populates the Global/area scope dropdown (always shown, with at least "Global" as an entry)
     * and, when an area is selected, its "Independent" toggle. When CustomAreas isn't installed (or
     * has no BLOCKREGEN-flagged areas), the dropdown simply has a single "Global" entry and behaves
     * exactly like the page did before this feature existed.
     */
    private void buildScopeSelector(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        CustomAreasBridge bridge = plugin.getCustomAreasBridge();
        bridge.ensureFlagRegistered(); // opportunistic retry, in case CustomAreas loaded after this plugin's setup()

        List<DropdownEntryInfo> scopeEntries = new ArrayList<>();
        scopeEntries.add(new DropdownEntryInfo(LocalizableString.fromString(SCOPE_GLOBAL), SCOPE_GLOBAL));
        for (String areaName : bridge.getBlockRegenAreaNames()) {
            scopeEntries.add(new DropdownEntryInfo(LocalizableString.fromString(areaName), areaName));
        }

        // If the selected area lost its BLOCKREGEN flag, was deleted, or CustomAreas is no longer
        // present, fall back to Global rather than pointing at a scope that no longer exists.
        if (selectedScope != null && scopeEntries.stream().noneMatch(e -> e.value().equals(selectedScope))) {
            selectedScope = null;
        }

        commandBuilder.set("#ScopeDropdown.Entries", scopeEntries);
        commandBuilder.set("#ScopeDropdown.Value", selectedScope == null ? SCOPE_GLOBAL : selectedScope);
        commandBuilder.set("#ScopeDropdown.TooltipText", Message.translation(BlockRegenMessages.UI_TOOLTIP_SCOPE_DROPDOWN));

        eventBuilder.addEventBinding(
            CustomUIEventBindingType.ValueChanged,
            "#ScopeDropdown",
            new EventData().append("@ScopeValue", "#ScopeDropdown.Value"),
            false
        );

        commandBuilder.clear("#IndependentToggle");
        if (selectedScope != null) {
            boolean independent = plugin.isAreaIndependent(selectedScope);
            commandBuilder.appendInline(
                "#IndependentToggle",
                toggleButtonMarkup("Independent", independent, independent ? "ON" : "OFF", tooltipText(BlockRegenMessages.UI_TOOLTIP_INDEPENDENT_TOGGLE))
            );
            eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#IndependentToggle #Independent",
                EventData.of("IndependentToggleArea", selectedScope),
                false
            );
        }
    }

    /**
     * Populates the "add a new rule" panel: every block currently known to the
     * server (vanilla or from any mod) as a searchable dropdown, a delay field
     * and its own S/M/H toggle. Only needs to run once (the dropdown entries
     * list is not resent on every list refresh).
     */
    private void buildAddSection(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        refreshAddBlockEntries(commandBuilder);
        commandBuilder.set("#AddBlock.TooltipText", Message.translation(BlockRegenMessages.UI_TOOLTIP_PICK_BLOCK));
        commandBuilder.set("#AddDelay.TooltipText", Message.translation(BlockRegenMessages.UI_TOOLTIP_ADD_DELAY));
        commandBuilder.set("#AddRadius.TooltipText", Message.translation(BlockRegenMessages.UI_TOOLTIP_ADD_RADIUS));
        commandBuilder.set("#AddButton.Text", Message.translation(BlockRegenMessages.UI_ADD_BUTTON));
        commandBuilder.set("#AddButton.TooltipText", Message.translation(BlockRegenMessages.UI_TOOLTIP_ADD_RULE));

        buildAddUnitToggle(commandBuilder, eventBuilder);
        buildAddFloorToggle(commandBuilder, eventBuilder);
        buildAddRegrowthToggle(commandBuilder, eventBuilder);

        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#AddButton",
            new EventData().append("@AddBlockId", "#AddBlock.Value").append("@AddDelay", "#AddDelay.Value").append("@AddRadius", "#AddRadius.Value"),
            false
        );
    }

    /**
     * Rebuilds the "add a new rule" block-picker dropdown entries. Blocks that already have a rule in the
     * currently selected scope (see {@link #rowBlockIdsForCurrentScope}) get a "[Set] " label prefix plus a
     * tooltip, so a long list stays easy to scan without relying on color alone. Refreshed on every interaction
     * (not just once) since "already configured" is scope-relative and must stay accurate when switching
     * Global/area or after adding/removing a rule.
     */
    private void refreshAddBlockEntries(@Nonnull UICommandBuilder commandBuilder) {
        Set<String> configuredBlockIds = rowBlockIdsForCurrentScope();
        LocalizableString alreadySetTooltip = LocalizableString.fromString(tooltipText(BlockRegenMessages.UI_TOOLTIP_ADD_BLOCK_ALREADY_SET));

        List<DropdownEntryInfo> blockEntries = new ArrayList<>();
        for (String blockId : BlockType.getAssetMap().getAssetMap().keySet()) {
            if (blockId == null) {
                continue;
            }
            boolean configured = configuredBlockIds.contains(blockId);
            blockEntries.add(configured
                ? new DropdownEntryInfo(displayLabel(blockId, true), blockId, alreadySetTooltip)
                : new DropdownEntryInfo(displayLabel(blockId, false), blockId));
        }
        blockEntries.sort((a, b) -> a.value().compareToIgnoreCase(b.value()));
        commandBuilder.set("#AddBlock.Entries", blockEntries);
    }

    /** Rebuilds the single ON/OFF "need floor" toggle button of the "add a new rule" panel. */
    private void buildAddFloorToggle(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        commandBuilder.clear("#AddFloorToggle");
        commandBuilder.appendInline(
            "#AddFloorToggle",
            toggleButtonMarkup("AddFloor", addNeedFloor, "Floor", tooltipText(BlockRegenMessages.UI_TOOLTIP_ADD_FLOOR))
        );

        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#AddFloorToggle #AddFloor",
            EventData.of("AddFloorToggle", addNeedFloor ? "false" : "true"),
            false
        );
    }

    /** Rebuilds the single ON/OFF "regrowth" toggle button of the "add a new rule" panel. */
    private void buildAddRegrowthToggle(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        commandBuilder.clear("#AddRegrowthToggle");
        commandBuilder.appendInline(
            "#AddRegrowthToggle",
            toggleButtonMarkup("AddRegrowth", addRegrowth, "Regrow", tooltipText(BlockRegenMessages.UI_TOOLTIP_ADD_REGROWTH))
        );

        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#AddRegrowthToggle #AddRegrowth",
            EventData.of("AddRegrowthToggle", addRegrowth ? "false" : "true"),
            false
        );
    }

    private void buildAddUnitToggle(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        commandBuilder.clear("#AddUnitToggle");
        commandBuilder.appendInline(
            "#AddUnitToggle",
            unitButtonMarkup("AddUnitS", UNIT_SECONDS, addUnit.equals(UNIT_SECONDS), 2, tooltipText(BlockRegenMessages.UI_TOOLTIP_UNIT_SECONDS))
                + unitButtonMarkup("AddUnitM", UNIT_MINUTES, addUnit.equals(UNIT_MINUTES), 2, tooltipText(BlockRegenMessages.UI_TOOLTIP_UNIT_MINUTES))
                + unitButtonMarkup("AddUnitH", UNIT_HOURS, addUnit.equals(UNIT_HOURS), 0, tooltipText(BlockRegenMessages.UI_TOOLTIP_UNIT_HOURS))
        );

        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#AddUnitToggle #AddUnitS",
            new EventData().append("AddUnit", UNIT_SECONDS).append("@AddCurrentValue", "#AddDelay.Value"),
            false
        );
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#AddUnitToggle #AddUnitM",
            new EventData().append("AddUnit", UNIT_MINUTES).append("@AddCurrentValue", "#AddDelay.Value"),
            false
        );
        eventBuilder.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#AddUnitToggle #AddUnitH",
            new EventData().append("AddUnit", UNIT_HOURS).append("@AddCurrentValue", "#AddDelay.Value"),
            false
        );
    }

    /**
     * Block ids to show as rows for the currently selected scope: the global rule set when Global
     * is selected; otherwise the selected area's own rule block ids, plus (unless that area is
     * marked Independent) every globally-configured block id too, so inherited rows show up.
     * Membership only - callers that display these ids sort them separately (see {@link #buildList}).
     */
    @Nonnull
    private Set<String> rowBlockIdsForCurrentScope() {
        Set<String> ids = new HashSet<>();
        if (selectedScope == null) {
            ids.addAll(plugin.getRules().keySet());
            return ids;
        }
        ids.addAll(plugin.getAreaRuleBlockIds(selectedScope));
        if (!plugin.isAreaIndependent(selectedScope)) {
            ids.addAll(plugin.getRules().keySet());
        }
        return ids;
    }

    private void buildList(@Nonnull UICommandBuilder commandBuilder, @Nonnull UIEventBuilder eventBuilder) {
        commandBuilder.clear("#BlockList");

        List<String> blockIds = new ArrayList<>(rowBlockIdsForCurrentScope());
        if (blockIds.isEmpty()) {
            commandBuilder.appendInline("#BlockList", "Label #EmptyLabel { Style: (Alignment: Center); }");
            commandBuilder.set("#BlockList #EmptyLabel.Text", Message.translation(BlockRegenMessages.UI_NO_BLOCK_CONFIGURED));
            return;
        }
        // Sorted by the name actually shown in the row (not the raw block id), case-insensitively,
        // so the list reads alphabetically the way the player sees it.
        blockIds.sort(Comparator.comparing(BlockRegenListPage::displayName, String.CASE_INSENSITIVE_ORDER));

        int index = 0;
        for (String blockId : blockIds) {
            BlockRegenPlugin.EffectiveRule rule = plugin.resolveEffectiveRule(selectedScope, blockId);
            if (rule == null) {
                continue; // defensive: shouldn't happen given how rowBlockIdsForCurrentScope() builds its set
            }
            int delaySeconds = rule.delaySeconds();
            String unit = displayUnitByBlockId.computeIfAbsent(blockId, id -> defaultUnitFor(delaySeconds));
            String selector = "#BlockList[" + index + "]";
            index++;

            Item item = resolveItem(blockId);

            commandBuilder.append("#BlockList", ROW_FILE);
            if (item != null) {
                commandBuilder.set(selector + " #Name.Text", item.getTranslationMessage());
            } else {
                commandBuilder.set(selector + " #Name.Text", blockId);
            }
            commandBuilder.set(selector + " #Delay.Value", delaySeconds / unitMultiplier(unit));
            commandBuilder.set(selector + " #Delay.TooltipText", Message.translation(BlockRegenMessages.UI_TOOLTIP_ROW_DELAY));
            commandBuilder.set(selector + " #Icon.Slots", new ItemGridSlot[]{new ItemGridSlot(new ItemStack(item != null ? item.getId() : blockId, 1))});
            commandBuilder.set(selector + " #Remove.Text", Message.translation(BlockRegenMessages.UI_REMOVE_BUTTON));
            commandBuilder.set(selector + " #Remove.TooltipText", Message.translation(BlockRegenMessages.UI_TOOLTIP_REMOVE_RULE));

            boolean needFloor = rule.needFloor();
            int radius = rule.radius();
            commandBuilder.set(selector + " #Radius.Value", radius);
            commandBuilder.set(selector + " #Radius.TooltipText", Message.translation(BlockRegenMessages.UI_TOOLTIP_ROW_RADIUS));

            String floorToggleSelector = selector + " #FloorToggle";
            commandBuilder.appendInline(floorToggleSelector, toggleButtonMarkup("Floor", needFloor, "Floor", tooltipText(BlockRegenMessages.UI_TOOLTIP_ROW_FLOOR)));
            eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                floorToggleSelector + " #Floor",
                new EventData().append("FloorBlockId", blockId).append("Floor", needFloor ? "false" : "true"),
                false
            );
            eventBuilder.addEventBinding(
                CustomUIEventBindingType.FocusLost,
                selector + " #Radius",
                new EventData().append("RadiusBlockId", blockId).append("@Radius", selector + " #Radius.Value")
            );

            boolean regrowth = rule.regrowth();
            String regrowthToggleSelector = selector + " #RegrowthToggle";
            commandBuilder.appendInline(regrowthToggleSelector, toggleButtonMarkup("Regrowth", regrowth, "Regrow", tooltipText(BlockRegenMessages.UI_TOOLTIP_ROW_REGROWTH)));
            eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                regrowthToggleSelector + " #Regrowth",
                new EventData().append("RegrowthBlockId", blockId).append("Regrowth", regrowth ? "false" : "true"),
                false
            );

            String toggleSelector = selector + " #UnitToggle";
            commandBuilder.appendInline(
                toggleSelector,
                unitButtonMarkup("UnitS", UNIT_SECONDS, unit.equals(UNIT_SECONDS), 2, tooltipText(BlockRegenMessages.UI_TOOLTIP_SHOW_UNIT_SECONDS))
                    + unitButtonMarkup("UnitM", UNIT_MINUTES, unit.equals(UNIT_MINUTES), 2, tooltipText(BlockRegenMessages.UI_TOOLTIP_SHOW_UNIT_MINUTES))
                    + unitButtonMarkup("UnitH", UNIT_HOURS, unit.equals(UNIT_HOURS), 0, tooltipText(BlockRegenMessages.UI_TOOLTIP_SHOW_UNIT_HOURS))
            );

            eventBuilder.addEventBinding(
                CustomUIEventBindingType.FocusLost,
                selector + " #Delay",
                new EventData().append("DelayBlockId", blockId).append("@Delay", selector + " #Delay.Value")
            );
            eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                toggleSelector + " #UnitS",
                new EventData().append("UnitBlockId", blockId).append("Unit", UNIT_SECONDS).append("@CurrentValue", selector + " #Delay.Value"),
                false
            );
            eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                toggleSelector + " #UnitM",
                new EventData().append("UnitBlockId", blockId).append("Unit", UNIT_MINUTES).append("@CurrentValue", selector + " #Delay.Value"),
                false
            );
            eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                toggleSelector + " #UnitH",
                new EventData().append("UnitBlockId", blockId).append("Unit", UNIT_HOURS).append("@CurrentValue", selector + " #Delay.Value"),
                false
            );
            eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                selector + " #Remove",
                EventData.of("RemoveBlockId", blockId),
                false
            );
        }
    }

    /**
     * Resolves a message id to plain text (server-side, en-US) for embedding
     * into inline UI markup strings, which -- unlike UICommandBuilder.set()
     * -- take literal text rather than a translatable Message object.
     */
    @Nonnull
    private static String tooltipText(@Nonnull String messageId) {
        return Message.translation(messageId).getAnsiMessage().replace("\"", "'");
    }

    /**
     * Builds one S/M/H toggle button as inline UI markup. Selected buttons get
     * a blue background so the active unit is visible at a glance; the label
     * text itself is left untouched (plain "S"/"M"/"H").
     */
    @Nonnull
    private static String unitButtonMarkup(@Nonnull String id, @Nonnull String letter, boolean selected, int rightMargin, @Nonnull String tooltip) {
        String background = selected ? "#3d7fda" : "#2b3542";
        String hoveredBackground = selected ? "#4d8fea" : "#3a4657";
        String textColor = selected ? "#ffffff" : "#96a9be";
        return "TextButton #" + id + " {"
            + " Anchor: (Width: 28, Height: 26, Right: " + rightMargin + ");"
            + " Padding: (Full: 4);"
            + " Style: ("
            + "   Default: (Background: " + background + ", LabelStyle: (TextColor: " + textColor + ", HorizontalAlignment: Center, VerticalAlignment: Center, FontSize: 13, RenderBold: true)),"
            + "   Hovered: (Background: " + hoveredBackground + ", LabelStyle: (TextColor: " + textColor + ", HorizontalAlignment: Center, VerticalAlignment: Center, FontSize: 13, RenderBold: true))"
            + " );"
            + " Text: \"" + letter + "\";"
            + " TooltipText: \"" + tooltip + "\";"
            + " TextTooltipStyle: (Background: (TexturePath: \"Common/TooltipDefaultBackground.png\", Border: 24), MaxWidth: 220, LabelStyle: (Wrap: true, FontSize: 14), Padding: 12);"
            + "}";
    }

    /**
     * Builds an ON/OFF toggle button (fixed label, color-coded background/text for the current state)
     * as inline UI markup, following the same visual language as {@link #unitButtonMarkup}.
     */
    @Nonnull
    private static String toggleButtonMarkup(@Nonnull String id, boolean on, @Nonnull String label, @Nonnull String tooltip) {
        String background = on ? "#3d7fda" : "#2b3542";
        String hoveredBackground = on ? "#4d8fea" : "#3a4657";
        String textColor = on ? "#ffffff" : "#96a9be";
        return "TextButton #" + id + " {"
            + " Anchor: (Width: 50, Height: 26, Right: 8);"
            + " Padding: (Full: 4);"
            + " Style: ("
            + "   Default: (Background: " + background + ", LabelStyle: (TextColor: " + textColor + ", HorizontalAlignment: Center, VerticalAlignment: Center, FontSize: 12, RenderBold: true)),"
            + "   Hovered: (Background: " + hoveredBackground + ", LabelStyle: (TextColor: " + textColor + ", HorizontalAlignment: Center, VerticalAlignment: Center, FontSize: 12, RenderBold: true))"
            + " );"
            + " Text: \"" + label + "\";"
            + " TooltipText: \"" + tooltip + "\";"
            + " TextTooltipStyle: (Background: (TexturePath: \"Common/TooltipDefaultBackground.png\", Border: 24), MaxWidth: 220, LabelStyle: (Wrap: true, FontSize: 14), Padding: 12);"
            + "}";
    }

    @Nonnull
    private static String defaultUnitFor(int delaySeconds) {
        if (delaySeconds % 3600 == 0) {
            return UNIT_HOURS;
        } else if (delaySeconds % 60 == 0) {
            return UNIT_MINUTES;
        } else {
            return UNIT_SECONDS;
        }
    }

    private static int unitMultiplier(@Nonnull String unit) {
        return switch (unit) {
            case UNIT_MINUTES -> 60;
            case UNIT_HOURS -> 3600;
            default -> 1;
        };
    }

    /** Blocks are shown (icon + display name) via their backing item; null if this block has none. */
    @Nullable
    private static Item resolveItem(@Nonnull String blockId) {
        BlockType blockType = BlockType.fromString(blockId);
        return blockType != null ? blockType.getItem() : null;
    }

    /**
     * Human-readable label for the "add a new rule" dropdown; falls back to the raw block id if it has no item.
     * When {@code configured} is true (this block already has a rule in the current scope), the label is
     * prefixed with a plain "[Set] " marker instead - there's no API to prepend literal text to a
     * message-id-based (per-viewer translated) label, so only this already-configured subset falls back to a
     * flattened plain-English name (see {@link #displayName}) to make room for the marker. Unconfigured blocks
     * (the vast majority) keep full per-viewer translation.
     */
    @Nonnull
    private static LocalizableString displayLabel(@Nonnull String blockId, boolean configured) {
        Item item = resolveItem(blockId);
        if (!configured) {
            return item != null ? LocalizableString.fromMessageId(item.getTranslationKey()) : LocalizableString.fromString(blockId);
        }
        return LocalizableString.fromString("[Set] " + displayName(blockId));
    }

    /** Flattened, plain-English (server-side, not per-viewer translated) display name; falls back to the raw block id. */
    @Nonnull
    private static String displayName(@Nonnull String blockId) {
        Item item = resolveItem(blockId);
        return item != null ? Message.translation(item.getTranslationKey()).getAnsiMessage() : blockId;
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull ListPageEventData data) {
        boolean listChanged = true;

        if (data.getDelayBlockId() != null && data.getDelay() != null) {
            applyDelay(data.getDelayBlockId(), data.getDelay(), displayUnitByBlockId.get(data.getDelayBlockId()));
        } else if (data.getUnitBlockId() != null && data.getUnit() != null && data.getCurrentValue() != null) {
            String blockId = data.getUnitBlockId();
            applyDelay(blockId, data.getCurrentValue(), displayUnitByBlockId.get(blockId));
            displayUnitByBlockId.put(blockId, data.getUnit());
        } else if (data.getRemoveBlockId() != null) {
            if (selectedScope == null) {
                plugin.removeRule(data.getRemoveBlockId());
            } else {
                plugin.removeAreaRule(selectedScope, data.getRemoveBlockId());
            }
            displayUnitByBlockId.remove(data.getRemoveBlockId());
        } else if (data.getFloorBlockId() != null && data.getFloor() != null) {
            boolean value = "true".equals(data.getFloor());
            if (selectedScope == null) {
                plugin.setNeedFloor(data.getFloorBlockId(), value);
            } else {
                plugin.setAreaNeedFloor(selectedScope, data.getFloorBlockId(), value);
            }
        } else if (data.getRadiusBlockId() != null && data.getRadius() != null) {
            int value = Math.max(0, data.getRadius());
            if (selectedScope == null) {
                plugin.setRadius(data.getRadiusBlockId(), value);
            } else {
                plugin.setAreaRadius(selectedScope, data.getRadiusBlockId(), value);
            }
        } else if (data.getRegrowthBlockId() != null && data.getRegrowth() != null) {
            boolean value = "true".equals(data.getRegrowth());
            if (selectedScope == null) {
                plugin.setRegrowth(data.getRegrowthBlockId(), value);
            } else {
                plugin.setAreaRegrowth(selectedScope, data.getRegrowthBlockId(), value);
            }
        } else if (data.getAddBlockId() != null) {
            addRule(data.getAddBlockId(), data.getAddDelay(), data.getAddRadius());
        } else if (data.getAddUnit() != null && data.getAddCurrentValue() != null) {
            addUnit = data.getAddUnit();
            listChanged = false;
        } else if (data.getAddFloorToggle() != null) {
            addNeedFloor = "true".equals(data.getAddFloorToggle());
            listChanged = false;
        } else if (data.getAddRegrowthToggle() != null) {
            addRegrowth = "true".equals(data.getAddRegrowthToggle());
            listChanged = false;
        } else if (data.getScopeValue() != null) {
            selectedScope = SCOPE_GLOBAL.equals(data.getScopeValue()) ? null : data.getScopeValue();
        } else if (data.getIndependentToggleArea() != null) {
            String area = data.getIndependentToggleArea();
            plugin.setAreaIndependent(area, !plugin.isAreaIndependent(area));
        } else {
            listChanged = false;
        }

        UICommandBuilder commandBuilder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        buildScopeSelector(commandBuilder, eventBuilder);
        refreshAddBlockEntries(commandBuilder);
        buildAddUnitToggle(commandBuilder, eventBuilder);
        buildAddFloorToggle(commandBuilder, eventBuilder);
        buildAddRegrowthToggle(commandBuilder, eventBuilder);
        if (listChanged) {
            buildList(commandBuilder, eventBuilder);
        }
        sendUpdate(commandBuilder, eventBuilder, false);
    }

    /** Converts a value entered under the given (previously displayed) unit back to seconds and saves it, in the current scope. */
    private void applyDelay(@Nonnull String blockId, int value, @Nullable String previousUnit) {
        int multiplier = unitMultiplier(previousUnit != null ? previousUnit : UNIT_SECONDS);
        int totalSeconds = value * multiplier;
        if (totalSeconds > 0) {
            if (selectedScope == null) {
                plugin.setRule(blockId, totalSeconds);
            } else {
                plugin.setAreaRule(selectedScope, blockId, totalSeconds);
            }
        }
    }

    /** Validates and adds a new rule from the "add a new rule" panel. */
    private void addRule(@Nonnull String blockName, @Nullable Integer delayInAddUnit, @Nullable Integer radius) {
        if (blockName.isBlank()) {
            playerRef.sendMessage(Message.translation(BlockRegenMessages.PICK_BLOCK_FIRST));
            return;
        }

        BlockType blockType = BlockType.fromString(blockName);
        if (blockType == null) {
            playerRef.sendMessage(Message.translation(BlockRegenMessages.UNKNOWN_BLOCK_SHORT).param("block", blockName));
            return;
        }

        int totalSeconds = (delayInAddUnit != null ? delayInAddUnit : 0) * unitMultiplier(addUnit);
        if (totalSeconds <= 0) {
            playerRef.sendMessage(Message.translation(BlockRegenMessages.ENTER_DELAY_FIRST));
            return;
        }

        String blockId = blockType.getId();
        int radiusValue = Math.max(0, radius != null ? radius : 0);
        if (selectedScope == null) {
            plugin.setRule(blockId, totalSeconds);
            plugin.setNeedFloor(blockId, addNeedFloor);
            plugin.setRadius(blockId, radiusValue);
            plugin.setRegrowth(blockId, addRegrowth);
        } else {
            plugin.setAreaRule(selectedScope, blockId, totalSeconds);
            plugin.setAreaNeedFloor(selectedScope, blockId, addNeedFloor);
            plugin.setAreaRadius(selectedScope, blockId, radiusValue);
            plugin.setAreaRegrowth(selectedScope, blockId, addRegrowth);
        }
        displayUnitByBlockId.put(blockId, addUnit);
        playerRef.sendMessage(Message.translation(BlockRegenMessages.RULE_SET)
            .param("block", blockType.getId())
            .param("duration", DurationParser.formatSeconds(totalSeconds)));
    }

    public static class ListPageEventData {
        @Nonnull
        public static final BuilderCodec<ListPageEventData> CODEC = BuilderCodec.builder(ListPageEventData.class, ListPageEventData::new)
            .append(new KeyedCodec<>("DelayBlockId", Codec.STRING), (e, s) -> e.delayBlockId = s, e -> e.delayBlockId)
            .add()
            .append(new KeyedCodec<>("@Delay", Codec.INTEGER), (e, i) -> e.delay = i, e -> e.delay)
            .add()
            .append(new KeyedCodec<>("UnitBlockId", Codec.STRING), (e, s) -> e.unitBlockId = s, e -> e.unitBlockId)
            .add()
            .append(new KeyedCodec<>("Unit", Codec.STRING), (e, s) -> e.unit = s, e -> e.unit)
            .add()
            .append(new KeyedCodec<>("@CurrentValue", Codec.INTEGER), (e, i) -> e.currentValue = i, e -> e.currentValue)
            .add()
            .append(new KeyedCodec<>("RemoveBlockId", Codec.STRING), (e, s) -> e.removeBlockId = s, e -> e.removeBlockId)
            .add()
            .append(new KeyedCodec<>("@AddBlockId", Codec.STRING), (e, s) -> e.addBlockId = s, e -> e.addBlockId)
            .add()
            .append(new KeyedCodec<>("@AddDelay", Codec.INTEGER), (e, i) -> e.addDelay = i, e -> e.addDelay)
            .add()
            .append(new KeyedCodec<>("AddUnit", Codec.STRING), (e, s) -> e.addUnit = s, e -> e.addUnit)
            .add()
            .append(new KeyedCodec<>("@AddCurrentValue", Codec.INTEGER), (e, i) -> e.addCurrentValue = i, e -> e.addCurrentValue)
            .add()
            .append(new KeyedCodec<>("FloorBlockId", Codec.STRING), (e, s) -> e.floorBlockId = s, e -> e.floorBlockId)
            .add()
            .append(new KeyedCodec<>("Floor", Codec.STRING), (e, s) -> e.floor = s, e -> e.floor)
            .add()
            .append(new KeyedCodec<>("RadiusBlockId", Codec.STRING), (e, s) -> e.radiusBlockId = s, e -> e.radiusBlockId)
            .add()
            .append(new KeyedCodec<>("@Radius", Codec.INTEGER), (e, i) -> e.radius = i, e -> e.radius)
            .add()
            .append(new KeyedCodec<>("AddFloorToggle", Codec.STRING), (e, s) -> e.addFloorToggle = s, e -> e.addFloorToggle)
            .add()
            .append(new KeyedCodec<>("@AddRadius", Codec.INTEGER), (e, i) -> e.addRadius = i, e -> e.addRadius)
            .add()
            .append(new KeyedCodec<>("RegrowthBlockId", Codec.STRING), (e, s) -> e.regrowthBlockId = s, e -> e.regrowthBlockId)
            .add()
            .append(new KeyedCodec<>("Regrowth", Codec.STRING), (e, s) -> e.regrowth = s, e -> e.regrowth)
            .add()
            .append(new KeyedCodec<>("AddRegrowthToggle", Codec.STRING), (e, s) -> e.addRegrowthToggle = s, e -> e.addRegrowthToggle)
            .add()
            .append(new KeyedCodec<>("@ScopeValue", Codec.STRING), (e, s) -> e.scopeValue = s, e -> e.scopeValue)
            .add()
            .append(new KeyedCodec<>("IndependentToggleArea", Codec.STRING), (e, s) -> e.independentToggleArea = s, e -> e.independentToggleArea)
            .add()
            .build();

        private String delayBlockId;
        private Integer delay;
        private String unitBlockId;
        private String unit;
        private Integer currentValue;
        private String removeBlockId;
        private String addBlockId;
        private Integer addDelay;
        private String addUnit;
        private Integer addCurrentValue;
        private String floorBlockId;
        private String floor;
        private String radiusBlockId;
        private Integer radius;
        private String addFloorToggle;
        private Integer addRadius;
        private String regrowthBlockId;
        private String regrowth;
        private String addRegrowthToggle;
        private String scopeValue;
        private String independentToggleArea;

        @Nullable
        public String getDelayBlockId() {
            return delayBlockId;
        }

        @Nullable
        public Integer getDelay() {
            return delay;
        }

        @Nullable
        public String getUnitBlockId() {
            return unitBlockId;
        }

        @Nullable
        public String getUnit() {
            return unit;
        }

        @Nullable
        public Integer getCurrentValue() {
            return currentValue;
        }

        @Nullable
        public String getRemoveBlockId() {
            return removeBlockId;
        }

        @Nullable
        public String getAddBlockId() {
            return addBlockId;
        }

        @Nullable
        public Integer getAddDelay() {
            return addDelay;
        }

        @Nullable
        public String getAddUnit() {
            return addUnit;
        }

        @Nullable
        public Integer getAddCurrentValue() {
            return addCurrentValue;
        }

        @Nullable
        public String getFloorBlockId() {
            return floorBlockId;
        }

        @Nullable
        public String getFloor() {
            return floor;
        }

        @Nullable
        public String getRadiusBlockId() {
            return radiusBlockId;
        }

        @Nullable
        public Integer getRadius() {
            return radius;
        }

        @Nullable
        public String getAddFloorToggle() {
            return addFloorToggle;
        }

        @Nullable
        public Integer getAddRadius() {
            return addRadius;
        }

        @Nullable
        public String getRegrowthBlockId() {
            return regrowthBlockId;
        }

        @Nullable
        public String getRegrowth() {
            return regrowth;
        }

        @Nullable
        public String getAddRegrowthToggle() {
            return addRegrowthToggle;
        }

        @Nullable
        public String getScopeValue() {
            return scopeValue;
        }

        @Nullable
        public String getIndependentToggleArea() {
            return independentToggleArea;
        }
    }
}
