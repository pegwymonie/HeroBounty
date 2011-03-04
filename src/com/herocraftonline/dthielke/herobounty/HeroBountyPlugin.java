/**
 * Copyright (C) 2011 DThielke <dave.thielke@gmail.com>
 * 
 * This work is licensed under the Creative Commons Attribution-NonCommercial-NoDerivs 3.0 Unported License.
 * To view a copy of this license, visit http://creativecommons.org/licenses/by-nc-nd/3.0/ or send a letter to
 * Creative Commons, 171 Second Street, Suite 300, San Francisco, California, 94105, USA.
 **/

package com.herocraftonline.dthielke.herobounty;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.Event.Type;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.config.Configuration;

import com.herocraftonline.dthielke.herobounty.util.Messaging;
import com.nijiko.coelho.iConomy.iConomy;
import com.nijiko.coelho.iConomy.system.Account;

public class HeroBountyPlugin extends JavaPlugin {
    private HeroBountyEntityListener entityListener = new HeroBountyEntityListener(this);

    private List<Bounty> bounties;
    private String bountyTag;
    private int bountyMin;
    private float bountyFeePercent;
    private float contractFeePercent;
    private float deathPenaltyPercent;
    private int bountyDuration; // in minutes
    private boolean payInconvenience;
    private boolean anonymousTargets;
    private boolean allowNegativeBalances;
    private int locationRounding;

    private Logger logger;

    private Timer expirationTimer = new Timer();

    public static final int EXPIRATION_TIMER_DELAY = 10000;
    public static final int EXPIRATION_TIMER_PERIOD = 1 * 60 * 1000;
    public static final String[] Colors = new String[] { "&c", "&e", "&f", "&7" };

    public boolean completeBounty(int id, String hunterName) {
        if (id < 0 || id >= bounties.size())
            return false;

        Bounty bounty = bounties.get(id);

        Player hunter = getServer().getPlayer(hunterName);
        if (hunter == null)
            return false;

        Player target = getServer().getPlayer(bounty.getTarget());
        if (target == null)
            return false;

        bounties.remove(id);
        Collections.sort(bounties);

        Account hunterAccount = iConomy.getBank().getAccount(hunter.getName());
        Account targetAccount = iConomy.getBank().getAccount(target.getName());
        double targetBalance = targetAccount.getBalance();
        double deathPenalty = bounty.getDeathPenalty();
        if (!allowNegativeBalances && (targetBalance < deathPenalty))
            deathPenalty = targetBalance;

        hunterAccount.add(bounty.getValue());
        targetAccount.subtract(deathPenalty);

        Messaging.broadcast(this,
                bountyTag + Colors[1] + hunter.getName() + Colors[0] + " has collected a bounty on " + Colors[1] + bounty.getTargetDisplayName() + Colors[0]
                        + " for " + Colors[1] + iConomy.getBank().format(bounty.getValue()) + Colors[0] + "!");
        Messaging.send(hunter, bountyTag + Colors[0] + "Well done! You have been awarded " + Colors[1] + iConomy.getBank().format(bounty.getValue())
                + Colors[0] + ".");

        if (deathPenalty > 0)
            Messaging.send(target, bountyTag + Colors[0] + "You have lost " + Colors[1] + iConomy.getBank().format(bounty.getDeathPenalty()) + Colors[0]
                    + " for falling victim to a bounty issued by " + Colors[1] + bounty.getOwnerDisplayName() + Colors[0] + "!");
        else
            Messaging.send(target, bountyTag + Colors[0] + "You have fallen victim to a bounty placed by " + Colors[1] + bounty.getOwnerDisplayName()
                    + Colors[0] + "!");

        saveData();

        return true;
    }

    public boolean checkIConomy() {
        Plugin test = this.getServer().getPluginManager().getPlugin("iConomy");
        boolean useIConomy = false;
        if (test != null) {
            useIConomy = true;
        }
        return useIConomy;

    }

    public List<Bounty> getBounties() {
        return bounties;
    }

    public String getBountyTag() {
        return bountyTag;
    }

    public boolean onCommand(CommandSender sender, Command command, String commandLabel, String[] args) {
        if (args.length == 0) {
            performShowHelp(sender, args);

            return true;
        }

        String commandName = args[0].toLowerCase();
        String[] trimmedArgs = new String[args.length - 1];

        for (int i = 0; i < args.length - 1; i++)
            trimmedArgs[i] = args[i + 1];

        if (commandName.equals("help"))
            performShowHelp(sender, trimmedArgs);
        else if (commandName.equals("new"))
            performNewBounty(sender, trimmedArgs);
        else if (commandName.equals("cancel"))
            performCancelBounty(sender, trimmedArgs);
        else if (commandName.equals("list"))
            performListBounties(sender, trimmedArgs);
        else if (commandName.equals("accept"))
            performAcceptBounty(sender, trimmedArgs);
        else if (commandName.equals("abandon"))
            performAbandonBounty(sender, trimmedArgs);
        else if (commandName.equals("view"))
            performViewBounties(sender, trimmedArgs);
        else if (commandName.equals("locate"))
            performLocateTarget(sender, trimmedArgs);

        return true;
    }

    @Override
    public void onDisable() {
        expirationTimer.cancel();
        expirationTimer.purge();

        PluginDescriptionFile pdfFile = this.getDescription();
        log(pdfFile.getName() + " version " + pdfFile.getVersion() + " disabled.");
    }

    @Override
    public void onEnable() {
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvent(Type.ENTITY_DEATH, entityListener, Priority.Normal, this);
        pm.registerEvent(Type.ENTITY_DAMAGED, entityListener, Priority.Normal, this);

        logger = Logger.getLogger("Minecraft");

        PluginDescriptionFile pdfFile = this.getDescription();
        log(pdfFile.getName() + " version " + pdfFile.getVersion() + " enabled.");

        Configuration config = getConfiguration();

        bountyMin = config.getInt("bounty-min", 20);
        bountyFeePercent = config.getInt("bounty-fee-percent", 10) / 100f;
        contractFeePercent = config.getInt("contract-fee-percent", 5) / 100f;
        deathPenaltyPercent = config.getInt("death-penalty-percent", 5) / 100f;
        bountyDuration = config.getInt("bounty-duration", 24 * 60);
        anonymousTargets = config.getBoolean("anonymous-targets", false);
        bountyTag = config.getString("bounty-tag", "&e[BOUNTY] ");
        payInconvenience = config.getBoolean("pay-inconvenience", true);
        allowNegativeBalances = config.getBoolean("allow-negative-balances", true);
        locationRounding = config.getInt("location-rounding", 100);

        File file = new File(getDataFolder(), "data.yml");
        bounties = BountyFileHandler.load(file);

        if (checkIConomy()) {
            TimerTask expirationChecker = new ExpirationChecker(this);
            getServer().getScheduler().scheduleAsyncRepeatingTask(this, expirationChecker, EXPIRATION_TIMER_DELAY, EXPIRATION_TIMER_PERIOD);
        } else {
            logger.log(Level.WARNING, "iConomy not found. Disabling.");
            pm.disablePlugin(this);
        }
    }

    public void log(String log) {
        logger.log(Level.INFO, "[HEROBOUNTY] " + log);
    }

    public void saveData() {
        File file = new File(getDataFolder(), "data.yml");
        BountyFileHandler.save(bounties, file);
    }

    public void setBounties(List<Bounty> bounties) {
        this.bounties = bounties;
    }

    public void setBountyTag(String bountyTag) {
        this.bountyTag = bountyTag;
    }

    public boolean isTarget(Player player) {
        String name = player.getName();
        for (Bounty bounty : bounties)
            if (bounty.getTarget().equals(name))
                return true;
        return false;
    }

    private List<Bounty> listBountiesAcceptedByPlayer(String hunter) {
        List<Bounty> acceptedBounties = new ArrayList<Bounty>();

        for (Bounty b : bounties)
            if (b.isHunter(hunter))
                acceptedBounties.add(b);

        return acceptedBounties;
    }

    private boolean performAbandonBounty(CommandSender sender, String[] args) {
        if (!(sender instanceof Player))
            return false;

        if (args.length != 1) {
            Messaging.send(sender, Colors[0] + "Usage: /bounty abandon <id#>");
            return false;
        }

        Player hunter = (Player) sender;

        List<Bounty> acceptedBounties = listBountiesAcceptedByPlayer(hunter.getName());

        int id = parseBountyId(args[0], acceptedBounties);

        if (id == -1) {
            Messaging.send(sender, bountyTag + Colors[0] + "Bounty not found.");
            return false;
        }

        acceptedBounties.get(id).removeHunter(hunter.getName());
        Messaging.send(sender, bountyTag + Colors[0] + "Bounty abandoned.");

        saveData();

        return false;
    }

    private boolean performAcceptBounty(CommandSender sender, String[] args) {
        if (!(sender instanceof Player))
            return false;

        if (args.length != 1) {
            Messaging.send(sender, Colors[0] + "Usage: /bounty accept <id#>");
            return false;
        }

        Player hunter = (Player) sender;
        String hunterName = hunter.getName();

        int id = parseBountyId(args[0], bounties);

        if (id < 0) {
            Messaging.send(sender, bountyTag + Colors[0] + "Bounty not found.");
            return false;
        }

        Bounty bounty = bounties.get(id);

        if (bounty.getOwner().equalsIgnoreCase(hunter.getName())) {
            Messaging.send(sender, bountyTag + Colors[0] + "You cannot accept your own bounty!");
            return false;
        }

        if (bounty.getTarget().equalsIgnoreCase(hunterName)) {
            Messaging.send(sender, bountyTag + Colors[0] + "You cannot accept this bounty!");
            return false;
        }

        if (bounty.isHunter(hunterName)) {
            Messaging.send(sender, bountyTag + Colors[0] + "You have already accepted this bounty!");
            return false;
        }

        Account hunterAccount = iConomy.getBank().getAccount(hunterName);
        double balance = hunterAccount.getBalance();
        if (balance < bounty.getContractFee()) {
            Messaging.send(sender, bountyTag + Colors[0] + "You don't have enough funds to do that!");
            return false;
        }

        bounty.addHunter(hunterName);

        GregorianCalendar expiration = new GregorianCalendar();
        expiration.add(Calendar.MINUTE, bountyDuration);
        bounty.getExpirations().put(hunterName, expiration.getTime());
        hunterAccount.subtract(bounty.getContractFee());

        int bountyRelativeTime = (bountyDuration < 60) ? bountyDuration : (bountyDuration < (60 * 24)) ? bountyDuration / 60
                : (bountyDuration < (60 * 24 * 7)) ? bountyDuration / (60 * 24) : bountyDuration / (60 * 24 * 7);

        String bountyRelativeAmount = (bountyDuration < 60) ? " minutes" : (bountyDuration < (60 * 24)) ? " hours" : (bountyDuration < (60 * 24 * 7)) ? " days"
                : " weeks";

        Messaging.send(sender,
                bountyTag + Colors[0] + "Bounty accepted. You have been charged a " + Colors[2] + iConomy.getBank().format(bounty.getContractFee())
                        + Colors[1] + " contract fee.");
        Messaging.send(sender, bountyTag + Colors[0] + "Target is " + Colors[2] + bounty.getTargetDisplayName() + Colors[1] + "! Bounty will expire in "
                + Colors[2] + bountyRelativeTime + Colors[1] + bountyRelativeAmount);

        Player owner = getServer().getPlayer(bounty.getOwner());

        if (owner != null) {
            Messaging.send(owner, Colors[0] + "Your bounty on " + Colors[1] + bounty.getTarget() + Colors[0] + " has been accepted by " + Colors[1]
                    + hunterName + Colors[0] + ".");
        }

        saveData();

        return true;
    }

    private boolean performCancelBounty(CommandSender sender, String[] args) {
        if (!(sender instanceof Player))
            return false;

        if (args.length != 1) {
            Messaging.send(sender, Colors[0] + "Usage: /bounty cancel <id#>");
            return false;
        }

        Player owner = (Player) sender;

        int id = parseBountyId(args[0], bounties);

        if (id == -1) {
            Messaging.send(sender, bountyTag + Colors[0] + "Bounty not found.");
            return false;
        }

        Bounty bounty = bounties.get(id);

        if (!bounty.getOwner().equalsIgnoreCase(owner.getName())) {
            Messaging.send(sender, bountyTag + Colors[0] + "You can only cancel bounties you created.");
            return false;
        }

        bounties.remove(bounty);
        Collections.sort(bounties);

        Account ownerAccount = iConomy.getBank().getAccount(owner.getName());
        ownerAccount.add(bounty.getValue());
        Messaging.send(sender, bountyTag + Colors[0] + "You have been reimbursed " + Colors[1] + iConomy.getBank().format(bounty.getValue()) + Colors[0]
                + " for your bounty.");

        int inconvenience = 0;

        List<String> hunters = bounty.getHunters();
        if (!hunters.isEmpty())
            inconvenience = (int) Math.floor((double) bounty.getPostingFee() / hunters.size());

        for (String hunterName : bounty.getHunters()) {
            Player hunter = getServer().getPlayer(hunterName);

            Account hunterAccount = iConomy.getBank().getAccount(hunterName);
            hunterAccount.add(bounty.getContractFee());

            if (payInconvenience)
                hunterAccount.add(inconvenience);

            if (hunter == null)
                continue;

            Messaging.send(hunter, bountyTag + Colors[0] + "The bounty you were pursuing targetting " + Colors[1] + bounty.getTarget() + Colors[0]
                    + " has been cancelled.");
            Messaging.send(hunter,
                    bountyTag + Colors[0] + "You have been reimbursed the " + Colors[1] + iConomy.getBank().format(bounty.getContractFee()) + Colors[0]
                            + " you paid for the bounty.");
            if (payInconvenience && inconvenience > 0)
                Messaging.send(hunter, bountyTag + Colors[0] + "You have received an additional " + Colors[1] + iConomy.getBank().format(inconvenience)
                        + Colors[0] + " for the inconvenience.");
        }

        saveData();

        return true;
    }

    private boolean performListBounties(CommandSender sender, String[] args) {
        if (!(sender instanceof Player))
            return false;

        String senderName = ((Player) sender).getName();

        int perPage = 7;
        int currentPage;

        if (args.length == 0)
            currentPage = 0;
        else
            currentPage = (args[0] == null) ? 0 : Integer.valueOf(args[0]);
        currentPage = (currentPage == 0) ? 1 : currentPage;
        int amountPages = (int) Math.ceil(bounties.size() / perPage) + 1;
        int pageStart = (currentPage - 1) * perPage;
        int pageEnd = pageStart + perPage - 1;
        pageEnd = (pageEnd >= bounties.size()) ? bounties.size() - 1 : pageEnd;

        if (bounties.isEmpty()) {
            Messaging.send(sender, bountyTag + Colors[0] + "No bounties currently listed.");
        } else if (currentPage > amountPages) {
            Messaging.send(sender, bountyTag + Colors[0] + "Invalid page number.");
        } else {
            Messaging.send(sender, Colors[0] + "Available Bounties (Page &f#" + currentPage + Colors[0] + " of &f" + amountPages + Colors[0] + " ):");

            for (int i = pageStart; i <= pageEnd; i++) {
                Bounty b = bounties.get(i);

                String msg = Colors[3] + (i + 1) + ". " + Colors[1];

                if (!anonymousTargets)
                    msg += b.getTarget() + Colors[3] + " - " + Colors[1];

                msg += iConomy.getBank().format(b.getValue()) + Colors[3] + " - " + Colors[1] + "Fee: "
                        + iConomy.getBank().format(b.getContractFee());

                if (senderName.equalsIgnoreCase(b.getOwner()))
                    msg += Colors[3] + " *YOURS*";

                Messaging.send(sender, msg);
            }
        }

        return true;
    }

    private boolean performLocateTarget(CommandSender sender, String[] args) {
        if (!(sender instanceof Player))
            return false;

        Player hunter = (Player) sender;
        String hunterName = hunter.getName();

        List<Bounty> acceptedBounties = listBountiesAcceptedByPlayer(hunterName);

        if (acceptedBounties.isEmpty()) {
            Messaging.send(hunter, bountyTag + Colors[0] + "You currently have no accepted bounties.");
        } else {
            Messaging.send(hunter, Colors[0] + "Last Known Target Locations: (x, z)");

            for (int i = 0; i < acceptedBounties.size(); i++) {
                Bounty b = acceptedBounties.get(i);
                Player target = getServer().getPlayer(b.getTarget());
                if (target == null) {
                    Messaging.send(hunter, Colors[2] + (i + 1) + ". " + Colors[1] + b.getTarget() + ": offline");
                } else {
                    Location loc = target.getLocation();
                    int x = loc.getBlockX();
                    int z = loc.getBlockZ();
                    x = (int) (Math.round(x / (float) locationRounding) * locationRounding);
                    z = (int) (Math.round(z / (float) locationRounding) * locationRounding);
                    Messaging.send(hunter, Colors[2] + (i + 1) + ". " + Colors[1] + b.getTarget() + ": " + "(" + x + ", " + z + ")");
                }
            }
        }

        return true;
    }

    private boolean performNewBounty(CommandSender sender, String[] args) {
        if (!(sender instanceof Player))
            return false;

        if (args.length != 2) {
            Messaging.send(sender, Colors[0] + "Usage: /bounty new <player> <value>");
            return false;
        }

        Player target = getServer().getPlayer(args[0]);
        if (target == null) {
            Messaging.send(sender, bountyTag + Colors[0] + "Target player not found.");
            return false;
        }

        Player owner = (Player) sender;

        if (target.getName().equals(owner.getName())) {
            Messaging.send(sender, bountyTag + Colors[0] + "You cannot place a bounty on yourself!");
            return false;
        }
        
        for (Bounty b : bounties) {
            if (b.getTarget().equalsIgnoreCase(target.getName())) {
                Messaging.send(sender, bountyTag + Colors[0] + "There is already a bounty on " + Colors[2] + target.getName() + Colors[0] + ".");
                return false;
            }
        }

        int value;

        try {
            value = Integer.parseInt(args[1]);

            if (value < bountyMin)
                throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Messaging.send(sender, bountyTag + Colors[0] + "Value must be a number greater than " + Colors[2] + bountyMin + Colors[0] + ".");
            return false;
        }

        Account ownerAccount = iConomy.getBank().getAccount(owner.getName());
        double balance = ownerAccount.getBalance();
        if (balance < value) {
            Messaging.send(sender, bountyTag + Colors[0] + "You don't have enough funds to do that!");
            return false;
        }

        int postingFee = (int) (bountyFeePercent * value);
        int award = value - postingFee;
        int contractFee = (int) (contractFeePercent * award);
        int deathPenalty = (int) (deathPenaltyPercent * award);

        Bounty bounty = new Bounty(owner.getName(), owner.getDisplayName(), target.getName(), target.getDisplayName(), award, postingFee, contractFee,
                deathPenalty);
        bounties.add(bounty);
        Collections.sort(bounties);

        ownerAccount.subtract(value);
        Messaging.send(sender, bountyTag + Colors[0] + "Placed a bounty on " + Colors[1] + target.getName() + Colors[0] + "'s head for " + Colors[1] + iConomy.getBank().format(award) + Colors[0] + ".");
        Messaging.send(sender, bountyTag + Colors[0] + "You have been charged a " + Colors[1] + iConomy.getBank().format(postingFee) + Colors[0]
                + " fee for posting a bounty.");
        Messaging.broadcast(this, bountyTag + Colors[1] + "A new bounty has been placed for " + Colors[2] + iConomy.getBank().format(award) + Colors[1] + ".");

        saveData();

        return true;
    }

    private boolean performShowHelp(CommandSender sender, String[] args) {
        Messaging.send(sender, Colors[0] + "-----[ " + Colors[2] + " Hero Bounty Help " + Colors[0] + " ]-----");
        Messaging.send(sender, Colors[1] + "/bounty help - Show this information.");
        Messaging.send(sender, Colors[1] + "/bounty new <player> <value> - Create a new bounty.");
        Messaging.send(sender, Colors[1] + "/bounty list <page#> - List bounties available.");
        Messaging.send(sender, Colors[1] + "/bounty accept <id#> - Accept bounty by id.");
        Messaging.send(sender, Colors[1] + "/bounty abandon <id#> - Abandon bounty by id.");
        Messaging.send(sender, Colors[1] + "/bounty cancel <id#> - Cancel bounty by id.");
        Messaging.send(sender, Colors[1] + "/bounty view - View your accepted bounties.");
        Messaging.send(sender, Colors[1] + "/bounty locate - Show last known target locations.");

        return false;
    }

    private boolean performViewBounties(CommandSender sender, String[] args) {
        if (!(sender instanceof Player))
            return false;

        Player hunter = (Player) sender;
        String hunterName = hunter.getName();

        List<Bounty> acceptedBounties = listBountiesAcceptedByPlayer(hunterName);

        if (acceptedBounties.isEmpty()) {
            Messaging.send(sender, bountyTag + Colors[0] + "You currently have no accepted bounties.");
        } else {
            Messaging.send(sender, Colors[0] + "Accepted Bounties:");
            for (int i = 0; i < acceptedBounties.size(); i++) {
                Bounty b = acceptedBounties.get(i);
                int bountyDuration = b.getMinutesLeft(hunterName);

                int bountyRelativeTime = (bountyDuration < 60) ? bountyDuration : (bountyDuration < (60 * 24)) ? bountyDuration / 60
                        : (bountyDuration < (60 * 24 * 7)) ? bountyDuration / (60 * 24) : bountyDuration / (60 * 24 * 7);

                String bountyRelativeAmount = (bountyDuration < 60) ? " minutes" : (bountyDuration < (60 * 24)) ? " hours"
                        : (bountyDuration < (60 * 24 * 7)) ? " days" : " weeks";

                Messaging.send(sender, Colors[2] + (i + 1) + ". " + Colors[1] + b.getTarget() + " - " + iConomy.getBank().format(b.getValue()) + " - "
                        + bountyRelativeTime + bountyRelativeAmount);
            }
        }

        return true;
    }

    public static int parseBountyId(String idStr, List<Bounty> bounties) {
        int id;

        try {
            id = Integer.parseInt(idStr) - 1;

            if (id < 0 || id >= bounties.size())
                throw new IndexOutOfBoundsException();
        } catch (Exception e) {
            return -1;
        }

        return id;
    }
}

class ExpirationChecker extends TimerTask {

    private HeroBountyPlugin plugin;

    public ExpirationChecker(HeroBountyPlugin plugin) {
        this.plugin = plugin;
    }

    public HeroBountyPlugin getPlugin() {
        return plugin;
    }

    @Override
    public void run() {
        Bounty[] bounties = plugin.getBounties().toArray(new Bounty[0]);
        for (Bounty bounty : bounties) {
            String[] hunters = bounty.getExpirations().keySet().toArray(new String[0]);
            for (String name : hunters) {
                if (bounty.getMillisecondsLeft(name) <= 0) {
                    Player hunter = plugin.getServer().getPlayer(name);

                    bounty.removeHunter(name);

                    plugin.saveData();

                    if (hunter == null)
                        continue;

                    Messaging.send(hunter,
                            plugin.getBountyTag() + HeroBountyPlugin.Colors[0] + "Your bounty on " + HeroBountyPlugin.Colors[1] + bounty.getTargetDisplayName()
                                    + HeroBountyPlugin.Colors[0] + " has expired!");
                }
            }
        }
    }

    public void setPlugin(HeroBountyPlugin plugin) {
        this.plugin = plugin;
    }

}
