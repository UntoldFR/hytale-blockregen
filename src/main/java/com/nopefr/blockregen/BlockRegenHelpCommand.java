package com.nopefr.blockregen;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * Usage :
 *   /blockregen help -> lists every /blockregen command with a one-line summary.
 */
public class BlockRegenHelpCommand extends AbstractCommand {

    public BlockRegenHelpCommand() {
        super("help", BlockRegenMessages.HELP_DESCRIPTION);
    }

    @Override
    @Nullable
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        Message message = Message.translation(BlockRegenMessages.HELP_HEADER)
            .insert("\n").insert(Message.translation(BlockRegenMessages.HELP_LINE_MAIN))
            .insert("\n").insert(Message.translation(BlockRegenMessages.HELP_LINE_TARGET))
            .insert("\n").insert(Message.translation(BlockRegenMessages.HELP_LINE_LIST))
            .insert("\n").insert(Message.translation(BlockRegenMessages.HELP_LINE_ADMIN));
        context.sendMessage(message);
        return CompletableFuture.completedFuture(null);
    }
}
