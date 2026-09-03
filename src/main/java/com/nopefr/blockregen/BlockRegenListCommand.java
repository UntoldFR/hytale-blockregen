package com.nopefr.blockregen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Usage :
 *   /blockregen list -> for a player, opens a UI window listing the blocks
 *                        currently configured to regenerate (icon, name,
 *                        editable delay, remove button). Falls back to a
 *                        plain text list for non-player senders (console).
 */
public class BlockRegenListCommand extends AbstractCommand {

    private final BlockRegenPlugin plugin;

    public BlockRegenListCommand(BlockRegenPlugin plugin) {
        super("list", BlockRegenMessages.LIST_DESCRIPTION);
        this.plugin = plugin;
    }

    @Override
    @Nullable
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (context.isPlayer()) {
            openListPage(context);
        } else {
            sendTextList(context);
        }
        return CompletableFuture.completedFuture(null);
    }

    private void openListPage(@Nonnull CommandContext context) {
        Ref<EntityStore> ref = context.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) {
            context.sendMessage(Message.translation(BlockRegenMessages.UNABLE_TO_RESOLVE_PLAYER));
            return;
        }

        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            Player playerComponent = store.getComponent(ref, Player.getComponentType());
            PlayerRef playerRefComponent = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerComponent == null || playerRefComponent == null) {
                return;
            }
            playerComponent.getPageManager().openCustomPage(ref, store, new BlockRegenListPage(playerRefComponent, plugin));
        });
    }

    private void sendTextList(@Nonnull CommandContext context) {
        Map<String, Integer> rules = plugin.getRules();
        if (rules.isEmpty()) {
            context.sendMessage(Message.translation(BlockRegenMessages.NO_ACTIVE_RULES));
            return;
        }

        Message message = Message.translation(BlockRegenMessages.ACTIVE_RULES_HEADER).param("count", rules.size());
        for (Map.Entry<String, Integer> entry : rules.entrySet()) {
            String blockId = entry.getKey();
            message.insert("\n - ").insert(blockId).insert(" : ").insert(DurationParser.formatSeconds(entry.getValue()));
            if (plugin.isNeedFloorFor(blockId)) {
                message.insert(", floor");
            }
            int radius = plugin.getRadiusFor(blockId);
            if (radius > 0) {
                message.insert(", radius ").insert(String.valueOf(radius));
            }
        }
        context.sendMessage(message);
    }
}
