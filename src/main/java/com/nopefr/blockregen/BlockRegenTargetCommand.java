package com.nopefr.blockregen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import javax.annotation.Nonnull;
import org.joml.Vector3i;

/**
 * Usage variant used when no block name is given, only a duration:
 *   /blockregen 90s
 * Resolves the block the player is currently looking at and asks for
 * confirmation in chat (Y/N) before applying the rule. See
 * {@link BlockRegenPlugin#handleChatConfirmation(PlayerRef, String)}.
 */
public class BlockRegenTargetCommand extends AbstractWorldCommand {

    private static final double MAX_TARGET_DISTANCE = 10.0;

    private final BlockRegenPlugin plugin;

    private final RequiredArg<String> durationArg;

    public BlockRegenTargetCommand(BlockRegenPlugin plugin) {
        super(BlockRegenMessages.TARGET_DESCRIPTION);
        this.plugin = plugin;

        durationArg = withRequiredArg("duration", BlockRegenMessages.ARG_DURATION, ArgTypes.STRING);
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
        if (!context.isPlayer()) {
            context.sendMessage(Message.translation(BlockRegenMessages.ONLY_PLAYER_CAN_TARGET));
            return;
        }

        Integer seconds = DurationParser.parseToSeconds(durationArg.get(context));
        if (seconds == null) {
            context.sendMessage(Message.translation(BlockRegenMessages.INVALID_DURATION).param("input", durationArg.get(context)));
            return;
        }

        PlayerRef senderPlayerRef = context.senderAs(PlayerRef.class);
        Ref<EntityStore> playerEntityRef = senderPlayerRef.getReference();
        if (playerEntityRef == null || !playerEntityRef.isValid()) {
            context.sendMessage(Message.translation(BlockRegenMessages.UNABLE_TO_RESOLVE_PLAYER));
            return;
        }

        Vector3i targetPos = TargetUtil.getTargetBlock(playerEntityRef, MAX_TARGET_DISTANCE, store);
        if (targetPos == null) {
            context.sendMessage(Message.translation(BlockRegenMessages.NO_BLOCK_IN_SIGHT));
            return;
        }

        WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(targetPos.x(), targetPos.z()));
        BlockType blockType = chunk == null ? null : chunk.getBlockType(targetPos.x(), targetPos.y(), targetPos.z());
        if (blockType == null || blockType == BlockType.EMPTY) {
            context.sendMessage(Message.translation(BlockRegenMessages.NO_BLOCK_IN_SIGHT));
            return;
        }

        plugin.setPendingConfirmation(senderPlayerRef.getUuid(), blockType.getId(), seconds);
        context.sendMessage(Message.translation(BlockRegenMessages.CONFIRM_TARGET_PROMPT)
            .param("duration", DurationParser.formatSeconds(seconds))
            .param("block", blockType.getId()));
    }
}
