package com.nopefr.blockregen;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.FlagArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * Usage :
 *   /blockregen "Stone" 120        -> "Stone" blocks respawn 120 seconds after being broken (no unit = minutes)
 *   /blockregen "Stone" 90s        -> same, but with an explicit unit (h/m/s)
 *   /blockregen "Poppy" 120 --floor --radius 3 -> also requires a floor and scatters the respawn within 3 blocks
 *   /blockregen "Wood_Oak_Trunk" 300 --regrowth -> plants a sapling instead of the log if there's still grass underneath
 *   /blockregen "Stone" 0 --remove -> removes the rule for "Stone"
 *   /blockregen list                -> lists configured blocks and their delay
 *   /blockregen 90s                 -> (see BlockRegenTargetCommand) applies to the block you are looking at, with confirmation
 */
public class BlockRegenCommand extends AbstractCommand {

    private final BlockRegenPlugin plugin;

    private final RequiredArg<String> blockNameArg;
    private final RequiredArg<String> durationArg;
    private final FlagArg removeFlag;
    private final FlagArg floorFlag;
    private final DefaultArg<Integer> radiusArg;
    private final FlagArg regrowthFlag;

    public BlockRegenCommand(BlockRegenPlugin plugin) {
        super("blockregen", BlockRegenMessages.COMMAND_DESCRIPTION);
        this.plugin = plugin;

        blockNameArg = withRequiredArg("block", BlockRegenMessages.ARG_BLOCK, ArgTypes.STRING);
        durationArg = withRequiredArg("duration", BlockRegenMessages.ARG_DURATION, ArgTypes.STRING);
        removeFlag = withFlagArg("remove", BlockRegenMessages.FLAG_REMOVE);
        floorFlag = withFlagArg("floor", BlockRegenMessages.FLAG_FLOOR);
        radiusArg = withDefaultArg("radius", BlockRegenMessages.ARG_RADIUS, ArgTypes.INTEGER, 0, "0");
        regrowthFlag = withFlagArg("regrowth", BlockRegenMessages.FLAG_REGROWTH);

        addSubCommand(new BlockRegenListCommand(plugin));
        addSubCommand(new BlockRegenAdminCommand(plugin));
        addSubCommand(new BlockRegenHelpCommand());
        addUsageVariant(new BlockRegenTargetCommand(plugin));

        // Only players/console with the right permission can use this
        // (auto-generated permission: com.nopefr.blockregen.command.blockregen)
    }

    @Override
    @Nullable
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        String blockName = blockNameArg.get(context);
        String durationInput = durationArg.get(context);

        // Make sure the block actually exists in the server's assets
        BlockType blockType = BlockType.fromString(blockName);
        if (blockType == null) {
            context.sendMessage(Message.translation(BlockRegenMessages.UNKNOWN_BLOCK).param("block", blockName));
            return CompletableFuture.completedFuture(null);
        }

        if (removeFlag.get(context)) {
            boolean removed = plugin.removeRule(blockType.getId());
            context.sendMessage(Message.translation(removed ? BlockRegenMessages.RULE_REMOVED : BlockRegenMessages.NO_RULE_EXISTED)
                .param("block", blockType.getId()));
            return CompletableFuture.completedFuture(null);
        }

        Integer seconds = DurationParser.parseToSeconds(durationInput);
        if (seconds == null) {
            context.sendMessage(Message.translation(BlockRegenMessages.INVALID_DURATION).param("input", durationInput));
            return CompletableFuture.completedFuture(null);
        }

        plugin.setRule(blockType.getId(), seconds);
        plugin.setNeedFloor(blockType.getId(), floorFlag.get(context));
        plugin.setRadius(blockType.getId(), radiusArg.get(context));
        plugin.setRegrowth(blockType.getId(), regrowthFlag.get(context));
        context.sendMessage(Message.translation(BlockRegenMessages.RULE_SET)
            .param("block", blockType.getId())
            .param("duration", DurationParser.formatSeconds(seconds)));

        return CompletableFuture.completedFuture(null);
    }
}
