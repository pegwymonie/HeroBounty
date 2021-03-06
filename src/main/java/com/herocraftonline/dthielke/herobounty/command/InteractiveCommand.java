/*
 * Copyright (c) 2012 David "DThielke" Thielke <dave.thielke@gmail.com>.
 * All rights reserved.
 */

package com.herocraftonline.dthielke.herobounty.command;

import org.bukkit.command.CommandSender;

public interface InteractiveCommand extends Command {
    String getCancelIdentifier();

    void onCommandCancelled(CommandSender executor);
}
