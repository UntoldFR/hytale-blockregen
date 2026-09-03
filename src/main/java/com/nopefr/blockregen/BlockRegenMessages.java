package com.nopefr.blockregen;

/**
 * Message IDs for every piece of text BlockRegen shows to players. The
 * actual wording lives in
 * src/main/resources/Server/Languages/en-US/blockregen.lang (loaded by the
 * game's own translation system) -- edit that file, or add another language
 * next to it, to reword or translate text without touching this class.
 */
final class BlockRegenMessages {

    private BlockRegenMessages() {
    }

    static final String COMMAND_DESCRIPTION = "blockregen.command.blockregen.description";
    static final String ARG_BLOCK = "blockregen.command.blockregen.arg.block";
    static final String ARG_DURATION = "blockregen.command.blockregen.arg.duration";
    static final String FLAG_REMOVE = "blockregen.command.blockregen.flag.remove";
    static final String FLAG_FLOOR = "blockregen.command.blockregen.flag.floor";
    static final String ARG_RADIUS = "blockregen.command.blockregen.arg.radius";
    static final String LIST_DESCRIPTION = "blockregen.command.list.description";
    static final String TARGET_DESCRIPTION = "blockregen.command.target.description";
    static final String ADMIN_DESCRIPTION = "blockregen.command.admin.description";

    static final String UNKNOWN_BLOCK = "blockregen.message.unknownBlock";
    static final String UNKNOWN_BLOCK_SHORT = "blockregen.message.unknownBlockShort";
    static final String RULE_REMOVED = "blockregen.message.ruleRemoved";
    static final String NO_RULE_EXISTED = "blockregen.message.noRuleExisted";
    static final String INVALID_DURATION = "blockregen.message.invalidDuration";
    static final String RULE_SET = "blockregen.message.ruleSet";
    static final String UNABLE_TO_RESOLVE_PLAYER = "blockregen.message.unableToResolvePlayer";
    static final String NO_ACTIVE_RULES = "blockregen.message.noActiveRules";
    static final String ACTIVE_RULES_HEADER = "blockregen.message.activeRulesHeader";
    static final String PICK_BLOCK_FIRST = "blockregen.message.pickBlockFirst";
    static final String ENTER_DELAY_FIRST = "blockregen.message.enterDelayFirst";
    static final String CANCELLED = "blockregen.message.cancelled";
    static final String CONFIRM_PROMPT = "blockregen.message.confirmPrompt";
    static final String ONLY_PLAYER_CAN_TARGET = "blockregen.message.onlyPlayerCanTarget";
    static final String NO_BLOCK_IN_SIGHT = "blockregen.message.noBlockInSight";
    static final String CONFIRM_TARGET_PROMPT = "blockregen.message.confirmTargetPrompt";
    static final String GHOST_NAMEPLATE = "blockregen.message.ghostNameplate";

    static final String ONLY_PLAYER_CAN_ADMIN = "blockregen.message.onlyPlayerCanAdmin";
    static final String ADMIN_BYPASS_ON_TITLE = "blockregen.message.adminBypassOnTitle";
    static final String ADMIN_BYPASS_OFF_TITLE = "blockregen.message.adminBypassOffTitle";
    static final String ADMIN_BYPASS_ON = "blockregen.message.adminBypassOn";
    static final String ADMIN_BYPASS_OFF = "blockregen.message.adminBypassOff";

    static final String UI_NO_BLOCK_CONFIGURED = "blockregen.ui.noBlockConfigured";
    static final String UI_ADD_BUTTON = "blockregen.ui.addButton";
    static final String UI_REMOVE_BUTTON = "blockregen.ui.removeButton";
    static final String UI_TOOLTIP_PICK_BLOCK = "blockregen.ui.tooltip.pickBlock";
    static final String UI_TOOLTIP_ADD_DELAY = "blockregen.ui.tooltip.addDelay";
    static final String UI_TOOLTIP_ADD_RULE = "blockregen.ui.tooltip.addRule";
    static final String UI_TOOLTIP_ROW_DELAY = "blockregen.ui.tooltip.rowDelay";
    static final String UI_TOOLTIP_REMOVE_RULE = "blockregen.ui.tooltip.removeRule";
    static final String UI_TOOLTIP_UNIT_SECONDS = "blockregen.ui.tooltip.unitSeconds";
    static final String UI_TOOLTIP_UNIT_MINUTES = "blockregen.ui.tooltip.unitMinutes";
    static final String UI_TOOLTIP_UNIT_HOURS = "blockregen.ui.tooltip.unitHours";
    static final String UI_TOOLTIP_SHOW_UNIT_SECONDS = "blockregen.ui.tooltip.showUnitSeconds";
    static final String UI_TOOLTIP_SHOW_UNIT_MINUTES = "blockregen.ui.tooltip.showUnitMinutes";
    static final String UI_TOOLTIP_SHOW_UNIT_HOURS = "blockregen.ui.tooltip.showUnitHours";

    static final String UI_TOOLTIP_ROW_FLOOR = "blockregen.ui.tooltip.rowFloor";
    static final String UI_TOOLTIP_ROW_RADIUS = "blockregen.ui.tooltip.rowRadius";
    static final String UI_TOOLTIP_ADD_FLOOR = "blockregen.ui.tooltip.addFloor";
    static final String UI_TOOLTIP_ADD_RADIUS = "blockregen.ui.tooltip.addRadius";

    static final String UI_TOOLTIP_SCOPE_DROPDOWN = "blockregen.ui.tooltip.scopeDropdown";
    static final String UI_TOOLTIP_INDEPENDENT_TOGGLE = "blockregen.ui.tooltip.independentToggle";
}
