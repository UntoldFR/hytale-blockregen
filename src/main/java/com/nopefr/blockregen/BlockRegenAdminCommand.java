package com.nopefr.blockregen;

import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * Usage :
 *   /blockregen admin -> bascule, pour le joueur qui l'execute, le mode
 *                         "bypass anti-abus" : tant qu'il est actif, les
 *                         blocs qu'il pose ne sont PAS exclus de la
 *                         regeneration (voir BlockRegenPlaceListener /
 *                         BlockRegenListener). Le nouvel etat est confirme
 *                         en jaune dans le chat et via une notification en
 *                         haut de l'ecran (le meme systeme que celui utilise
 *                         par le jeu pour les decouvertes de zone).
 */
public class BlockRegenAdminCommand extends AbstractCommand {

    private final BlockRegenPlugin plugin;

    public BlockRegenAdminCommand(BlockRegenPlugin plugin) {
        super("admin", BlockRegenMessages.ADMIN_DESCRIPTION);
        this.plugin = plugin;
    }

    @Override
    @Nullable
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (!context.isPlayer()) {
            context.sendMessage(Message.translation(BlockRegenMessages.ONLY_PLAYER_CAN_ADMIN));
            return CompletableFuture.completedFuture(null);
        }

        PlayerRef senderPlayerRef = context.senderAs(PlayerRef.class);
        boolean enabled = plugin.toggleAdminBypass(senderPlayerRef.getUuid());

        String titleKey = enabled ? BlockRegenMessages.ADMIN_BYPASS_ON_TITLE : BlockRegenMessages.ADMIN_BYPASS_OFF_TITLE;
        String detailKey = enabled ? BlockRegenMessages.ADMIN_BYPASS_ON : BlockRegenMessages.ADMIN_BYPASS_OFF;

        senderPlayerRef.sendMessage(Message.translation(titleKey)
            .insert(" - ")
            .insert(Message.translation(detailKey))
            .color("#FFFF00"));

        NotificationUtil.sendNotification(
            senderPlayerRef.getPacketHandler(),
            Message.translation(titleKey),
            Message.translation(detailKey),
            NotificationStyle.Warning
        );

        return CompletableFuture.completedFuture(null);
    }
}
