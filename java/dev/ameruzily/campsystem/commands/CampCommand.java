package dev.ameruzily.campsystem.commands;

import dev.ameruzily.campsystem.CampSystem;
import dev.ameruzily.campsystem.managers.StateManager;
import dev.ameruzily.campsystem.managers.WarManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class CampCommand implements CommandExecutor {

    private final CampSystem plugin;

    public CampCommand(CampSystem plugin) {
        this.plugin = plugin;
        plugin.getCommand("goc").setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.lang().send(null, "general.not-player");
            return true;
        }

        if (args.length == 0) {
            plugin.lang().send(p, "camp.help-header");
            for (String line : plugin.lang().list("camp.help-lines")) p.sendMessage(line);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("reload")) {
            if (!p.hasPermission("camprpg.admin")) {
                plugin.lang().send(p, "general.no-permission");
                return true;
            }
            plugin.config().reload();
            plugin.lang().reload();
            plugin.ideology().reload();
            plugin.placeholders().reload();
            plugin.loadSounds();
            if (plugin.campInfo() != null) {
                plugin.campInfo().reload();
            }
            if (plugin.protection() != null) {
                plugin.protection().reloadSettings();
            }
            if (plugin.holograms() != null) {
                plugin.holograms().reload();
            }
            if (plugin.gui() != null) {
                plugin.gui().reload();
            }
            if (plugin.war() != null) {
                plugin.war().reloadSettings();
            }
            plugin.refreshEconomy();
            plugin.state().startBankTask();
            plugin.lang().send(p, "general.reloaded");
            return true;
        }

        if (sub.equals("admin")) {
            if (!p.hasPermission("camprpg.admin")) {
                plugin.lang().send(p, "general.no-permission");
                return true;
            }

            if (args.length >= 2 && args[1].equalsIgnoreCase("stopwar")) {
                if (args.length < 4) {
                    plugin.lang().send(p, "war.admin-stop-usage");
                    return true;
                }

                StateManager.StateData first = plugin.state().findState(args[2]);
                StateManager.StateData second = plugin.state().findState(args[3]);
                if (first == null) {
                    plugin.lang().send(p, "war.target-not-found", Map.of("state", args[2]));
                    return true;
                }
                if (second == null) {
                    plugin.lang().send(p, "war.target-not-found", Map.of("state", args[3]));
                    return true;
                }

                if (plugin.war().adminStopWar(first.name, second.name) == null) {
                    plugin.lang().send(p, "war.admin-stop-none");
                } else {
                    plugin.lang().broadcast("war.admin-stop-success", Map.of(
                            "state1", first.name,
                            "state2", second.name
                    ));
                }
                return true;
            }

            plugin.lang().send(p, "camp.unknown");
            return true;
        }

        if (sub.equals("newstate") || sub.equals("create")) {
            if (plugin.state().hasPendingPlacement(p)) {
                plugin.lang().send(p, "state.pending-placement");
                return true;
            }
            if (plugin.state().hasState(p)) {
                plugin.lang().send(p, "state.already-member");
                return true;
            }
            if (!plugin.state().hasRequiredCampItem(p)) {
                plugin.lang().send(p, "state.no-camp-item");
                return true;
            }
            if (args.length < 3) {
                plugin.state().createState(p, "", "");
            } else {
                plugin.state().createState(p, args[1], args[2]);
            }
            return true;
        }

        if (sub.equals("newsector")) {
            if (!plugin.state().hasState(p)) {
                plugin.lang().send(p, "camp.not-found");
                return true;
            }
            if (!plugin.state().hasRequiredCampItem(p)) {
                plugin.lang().send(p, "state.no-camp-item");
                return true;
            }
            String sectorName = args.length >= 2 ? args[1] : "";
            plugin.state().prepareNewSector(p, sectorName);
            return true;
        }

        if (sub.equals("tp")) {
            if (args.length < 2) {
                plugin.lang().send(p, "state.sector-name-required");
                return true;
            }

            StateManager.TeleportResult result = plugin.state().teleport(p, args[1]);
            switch (result.getStatus()) {
                case NO_STATE -> plugin.lang().send(p, "camp.not-found");
                case SECTOR_NOT_FOUND -> plugin.lang().send(p, "state.sector-not-found", Map.of("sector", args[1]));
                case MISSING_LOCATION -> plugin.lang().send(p, "state.sector-move-missing", Map.of("sector", args[1]));
                case SUCCESS -> plugin.lang().send(p, "state.teleport-success", Map.of("sector", result.getSector()));
            }
            return true;
        }

        if (sub.equals("newgovernor") || sub.equals("setgovernor")) {
            if (args.length < 3) {
                plugin.lang().send(p, "state.newgovernor-usage");
                return true;
            }

            String playerName = args[1];
            String sectorName = args[2];
            StateManager.AssignGovernorResult result = plugin.state().assignGovernor(p, playerName, sectorName);
            switch (result.getStatus()) {
                case NOT_IN_STATE -> plugin.lang().send(p, "camp.not-found");
                case NOT_CAPTAIN -> plugin.lang().send(p, "state.setgovernor-not-captain");
                case PLAYER_NOT_FOUND -> plugin.lang().send(p, "state.setgovernor-player-not-found", Map.of(
                        "player", result.getPlayerName() != null ? result.getPlayerName() : playerName
                ));
                case PLAYER_NOT_MEMBER -> plugin.lang().send(p, "state.setgovernor-player-not-member", Map.of(
                        "player", result.getPlayerName() != null ? result.getPlayerName() : playerName
                ));
                case NO_SUCH_SECTOR -> plugin.lang().send(p, "state.sector-not-found", Map.of(
                        "sector", result.getSector() != null ? result.getSector() : sectorName
                ));
                case ALREADY_OWNER -> plugin.lang().send(p, "state.setgovernor-already", Map.of(
                        "player", result.getPlayerName() != null ? result.getPlayerName() : playerName,
                        "sector", result.getSector() != null ? result.getSector() : sectorName
                ));
                case SUCCESS -> {
                    Map<String, String> vars = Map.of(
                            "player", result.getPlayerName() != null ? result.getPlayerName() : playerName,
                            "sector", result.getSector() != null ? result.getSector() : sectorName
                    );
                    plugin.lang().send(p, "state.setgovernor-success", vars);
                    if (result.getPreviousSector() != null) {
                        plugin.lang().send(p, "state.setgovernor-reassigned", Map.of(
                                "player", vars.get("player"),
                                "old_sector", result.getPreviousSector()
                        ));
                    }
                    if (result.getPlayerId() != null) {
                        Player target = Bukkit.getPlayer(result.getPlayerId());
                        if (target != null && !target.getUniqueId().equals(p.getUniqueId())) {
                            plugin.lang().send(target, "state.setgovernor-notify", Map.of(
                                    "sector", vars.get("sector")
                            ));
                        }
                    }
                }
            }
            return true;
        }

        if (sub.equals("delgovernor")) {
            if (args.length < 2) {
                plugin.lang().send(p, "state.delgovernor-usage");
                return true;
            }

            String targetName = args[1];
            StateManager.RemoveGovernorResult result = plugin.state().removeGovernor(p, targetName);
            switch (result.getStatus()) {
                case NOT_IN_STATE -> plugin.lang().send(p, "camp.not-found");
                case NOT_CAPTAIN -> plugin.lang().send(p, "state.setgovernor-not-captain");
                case PLAYER_NOT_FOUND -> plugin.lang().send(p, "state.setgovernor-player-not-found", Map.of("player", targetName));
                case PLAYER_NOT_MEMBER -> plugin.lang().send(p, "state.setgovernor-player-not-member", Map.of("player", result.getPlayerName() == null ? targetName : result.getPlayerName()));
                case NOT_GOVERNOR -> plugin.lang().send(p, "state.delgovernor-not-governor", Map.of("player", result.getPlayerName() == null ? targetName : result.getPlayerName()));
                case SUCCESS -> {
                    String cleanedPlayer = result.getPlayerName() == null ? targetName : result.getPlayerName();
                    plugin.lang().send(p, "state.delgovernor-success", Map.of(
                            "player", cleanedPlayer,
                            "sector", result.getSector() == null ? plugin.lang().messageOrDefault("placeholders.none", "无") : result.getSector()
                    ));
                    if (result.getPlayerId() != null) {
                        Player target = Bukkit.getPlayer(result.getPlayerId());
                        if (target != null && !target.getUniqueId().equals(p.getUniqueId())) {
                            plugin.lang().send(target, "state.delgovernor-notify", Map.of(
                                    "sector", result.getSector() == null ? plugin.lang().messageOrDefault("placeholders.none", "无") : result.getSector()
                            ));
                        }
                    }
                }
            }
            return true;
        }

        if (sub.equals("givesector")) {
            if (args.length >= 2 && (args[1].equalsIgnoreCase("accept") || args[1].equalsIgnoreCase("deny"))) {
                boolean accept = args[1].equalsIgnoreCase("accept");
                String sourceState = args.length >= 3 ? args[2] : "";
                String sector = args.length >= 4 ? args[3] : "";

                StateManager.SectorGiftResponseResult response = plugin.state().respondSectorGift(p, accept, sourceState, sector);
                switch (response.getStatus()) {
                    case NO_STATE -> plugin.lang().send(p, "camp.not-found");
                    case NO_REQUEST -> plugin.lang().send(p, "state.givesector-none");
                    case MULTIPLE -> {
                        String separator = plugin.lang().messageOrDefault("state.join-request-separator", ", ");
                        StringJoiner joiner = new StringJoiner(separator);
                        for (StateManager.SectorGiftRequest req : response.getPending()) {
                            joiner.add(req.getSourceState() + ":" + req.getSector());
                        }
                        plugin.lang().send(p, "state.givesector-multiple", Map.of("requests", joiner.toString()));
                    }
                    case EXPIRED -> {
                        StateManager.SectorGiftRequest req = response.getRequest();
                        if (req != null) {
                            plugin.lang().send(p, "state.givesector-expired", Map.of(
                                    "state", req.getSourceState(),
                                    "sector", req.getSector()
                            ));
                        } else {
                            plugin.lang().send(p, "state.givesector-expired", Map.of("state", "?", "sector", "?"));
                        }
                    }
                    case TRANSFER_FAILED -> plugin.lang().send(p, "state.givesector-transfer-failed");
                    case DENIED -> {
                        StateManager.SectorGiftRequest req = response.getRequest();
                        if (req != null) {
                            plugin.lang().send(p, "state.givesector-denied", Map.of(
                                    "state", req.getSourceState(),
                                    "sector", req.getSector()
                            ));
                            StateManager.StateData giverState = plugin.state().findState(req.getSourceState());
                            Player giver = giverState != null ? Bukkit.getPlayer(giverState.captain) : null;
                            if (giver != null) {
                                plugin.lang().send(giver, "state.givesector-denied-notify", Map.of(
                                        "state", p.getName(),
                                        "sector", req.getSector()
                                ));
                            }
                        } else {
                            plugin.lang().send(p, "state.givesector-denied", Map.of("state", "?", "sector", "?"));
                        }
                    }
                    case ACCEPTED -> {
                        StateManager.SectorGiftRequest req = response.getRequest();
                        String newSector = response.getNewSector();
                        if (req != null) {
                            plugin.lang().send(p, "state.givesector-accepted", Map.of(
                                    "state", req.getSourceState(),
                                    "sector", newSector == null ? req.getSector() : newSector
                            ));
                            StateManager.StateData giverState = plugin.state().findState(req.getSourceState());
                            Player giver = giverState != null ? Bukkit.getPlayer(giverState.captain) : null;
                            if (giver != null) {
                                Map<String, String> notifyVars = new HashMap<>();
                                notifyVars.put("state", plugin.state().getStateName(p));
                                notifyVars.put("sector", newSector == null ? req.getSector() : newSector);
                                plugin.lang().send(giver, "state.givesector-accepted-notify", notifyVars);
                            }
                        } else {
                            plugin.lang().send(p, "state.givesector-accepted", Map.of("state", "?", "sector", "?"));
                        }
                    }
                }
                return true;
            }

            if (args.length < 3) {
                plugin.lang().send(p, "state.givesector-usage");
                return true;
            }

            StateManager.SectorGiftRequestResult result = plugin.state().requestSectorGift(p, args[1], args[2]);
            switch (result.getStatus()) {
                case NO_STATE -> plugin.lang().send(p, "camp.not-found");
                case NOT_CAPTAIN -> plugin.lang().send(p, "war.not-captain");
                case TARGET_NOT_FOUND -> plugin.lang().send(p, "state.givesector-target-not-found", Map.of("state", args[2]));
                case TARGET_OFFLINE -> plugin.lang().send(p, "state.givesector-target-offline", Map.of("state", result.getTargetState()));
                case NO_SUCH_SECTOR -> plugin.lang().send(p, "state.sector-not-found", Map.of("sector", args[1]));
                case CAPITAL_SECTOR -> plugin.lang().send(p, "state.sector-capital-protected");
                case ALREADY_PENDING -> {
                    Map<String, String> vars = new HashMap<>();
                    vars.put("state", result.getTargetState());
                    vars.put("sector", result.getSector() == null ? args[1] : result.getSector());
                    vars.put("time", plugin.war().formatDuration(result.getRemainingMs()));
                    plugin.lang().send(p, "state.givesector-pending", vars);
                }
                case SAME_STATE -> plugin.lang().send(p, "state.same-state");
                case SUCCESS -> {
                    Map<String, String> vars = new HashMap<>();
                    vars.put("state", result.getTargetState());
                    vars.put("sector", result.getSector() == null ? args[1] : result.getSector());
                    vars.put("time", plugin.war().formatDuration(result.getRemainingMs()));
                    plugin.lang().send(p, "state.givesector-requested", vars);

                    StateManager.StateData target = plugin.state().findState(result.getTargetState());
                    Player targetCaptain = target != null ? Bukkit.getPlayer(target.captain) : null;
                    if (targetCaptain != null) {
                        plugin.lang().send(targetCaptain, "state.givesector-notify", Map.of(
                                "state", plugin.state().getStateName(p),
                                "sector", result.getSector() == null ? args[1] : result.getSector(),
                                "time", plugin.war().formatDuration(result.getRemainingMs())
                        ));
                    }
                }
            }
            return true;
        }

        if (sub.equals("delsector") || sub.equals("removesector")) {
            if (args.length < 2) {
                plugin.lang().send(p, "state.sector-name-required");
                return true;
            }
            StateManager.RemoveSectorResult result = plugin.state().removeSector(p, args[1]);
            switch (result.getStatus()) {
                case NOT_IN_STATE -> plugin.lang().send(p, "camp.not-found");
                case NO_SUCH_SECTOR -> plugin.lang().send(p, "state.sector-not-found", Map.of("sector", args[1]));
                case NOT_AUTHORIZED -> plugin.lang().send(p, "state.sector-no-permission");
                case CAPITAL_SECTOR -> plugin.lang().send(p, "state.sector-capital-protected");
                case SUCCESS -> {
                    String sector = result.getSector() != null ? result.getSector() : args[1];
                    plugin.lang().send(p, "state.sector-remove-success", Map.of("sector", sector));
                    if (result.getNewCapital() != null) {
                        plugin.lang().sendActionBar(p, "state.capital-set", Map.of("sector", result.getNewCapital()));
                        if (plugin.capitalSetSound() != null) {
                            plugin.capitalSetSound().play(p);
                        }
                    } else if (result.isCapitalCleared()) {
                        plugin.lang().send(p, "state.capital-cleared");
                    }
                }
            }
            return true;
        }

        if (sub.equals("invite")) {
            if (args.length < 2) {
                plugin.lang().send(p, "state.invite-usage");
                return true;
            }

            String action = args[1].toLowerCase();
            if (action.equals("accept")) {
                plugin.state().acceptInvite(p);
                return true;
            }
            if (action.equals("deny")) {
                plugin.state().denyInvite(p);
                return true;
            }

            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                plugin.lang().send(p, "state.invite-target-offline", Map.of("player", args[1]));
                return true;
            }
            plugin.state().invite(p, target);
            return true;
        }

        if (sub.equals("list")) {
            String worldName = p.getWorld().getName();
            List<StateManager.StateData> states = new ArrayList<>();
            for (StateManager.StateData data : plugin.state().getStates()) {
                boolean matches = false;
                for (StateManager.SectorData sector : data.sectors.values()) {
                    Location loc = sector.getLocation();
                    if (loc != null && loc.getWorld() != null && worldName.equals(loc.getWorld().getName())) {
                        matches = true;
                        break;
                    }
                }
                if (matches) {
                    states.add(data);
                }
            }

            if (states.isEmpty()) {
                plugin.lang().send(p, "state.list-empty", Map.of("world", worldName));
                return true;
            }

            states.sort(Comparator.comparing(data -> data.name.toLowerCase(Locale.ROOT)));
            plugin.lang().send(p, "state.list-header", Map.of(
                    "world", worldName,
                    "count", String.valueOf(states.size())
            ));

            for (StateManager.StateData data : states) {
                Map<String, String> vars = new HashMap<>();
                vars.put("state", data.name);
                vars.put("ideology", plugin.state().getIdeologyDisplay(data.name));
                vars.put("sectors", String.valueOf(data.sectors.size()));
                vars.put("members", String.valueOf(data.members.size()));
                vars.put("captain", plugin.state().getOfflinePlayerName(data.captain));
                String capital = data.capitalSector == null
                        ? plugin.lang().messageOrDefault("placeholders.none", "无")
                        : data.capitalSector;
                vars.put("capital", capital);
                plugin.lang().send(p, "state.list-entry", vars);
            }
            return true;
        }

        if (sub.equals("join")) {
            if (args.length < 2) {
                plugin.lang().send(p, "state.join-usage");
                return true;
            }

            String action = args[1].toLowerCase();
            if (action.equals("accept") || action.equals("deny")) {
                String targetName = args.length >= 3 ? args[2] : "";
                plugin.state().respondJoinRequest(p, action.equals("accept"), targetName);
                return true;
            }

            plugin.state().requestJoin(p, args[1]);
            return true;
        }

        if (sub.equals("leave")) {
            plugin.state().leaveState(p);
            return true;
        }

        if (sub.equals("delstate") || sub.equals("delete")) {
            plugin.state().deleteState(p);
            return true;
        }

        if (sub.equals("maintain") || sub.equals("matain") || sub.equals("repair")) {
            plugin.lang().send(p, "state.legacy-maintenance-disabled");
            return true;
        }

        if (sub.equals("kick")) {
            if (args.length < 2) {
                plugin.lang().send(p, "camp.not-found");
                return true;
            }
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                plugin.lang().send(p, "camp.not-found");
                return true;
            }
            plugin.state().kickMember(p, target);
            return true;
        }

        if (sub.equals("info")) {
            String stateName = plugin.state().getStateName(p);
            if (stateName == null) {
                plugin.lang().send(p, "camp.not-found");
                return true;
            }
            StateManager.StateData s = plugin.state().getState(stateName);
            if (s == null) {
                return true;
            }

            plugin.lang().send(p, "campinfo.header");
            plugin.lang().send(p, "campinfo.title", Map.of("state_display", s.name));
            String ideologyDisplay = plugin.state().getIdeologyDisplay(s.name);
            plugin.lang().send(p, "campinfo.subtitle", Map.of("ideology_display", ideologyDisplay));
            StringJoiner joiner = new StringJoiner(plugin.lang().messageOrDefault("campinfo.sector-separator", ", "));
            s.sectors.keySet().forEach(joiner::add);
            String sectorText = s.sectors.isEmpty()
                    ? plugin.lang().messageOrDefault("campinfo.no-sector", "无")
                    : joiner.toString();
            plugin.lang().send(p, "campinfo.sector", Map.of("sector_name", sectorText));
            String captainName = Optional.ofNullable(Bukkit.getOfflinePlayer(s.captain).getName())
                    .filter(name -> !name.isEmpty())
                    .orElse(plugin.lang().messageOrDefault("bank.log-unknown", "未知"));
            plugin.lang().send(p, "campinfo.owner", Map.of("captain", captainName));
            plugin.lang().send(p, "campinfo.members", Map.of("member_count", String.valueOf(s.members.size())));
            if (s.capitalSector != null) {
                plugin.lang().send(p, "campinfo.capital", Map.of("sector", s.capitalSector));
            }
            plugin.lang().send(p, "campinfo.footer");
            return true;
        }

        if (sub.equals("bank")) {
            if (!plugin.getConfig().getBoolean("bank.enabled", true)) {
                plugin.lang().send(p, "bank.disabled");
                return true;
            }

            if (plugin.economy() == null) {
                plugin.lang().send(p, "bank.no-economy");
                return true;
            }

            String stateName = plugin.state().getStateName(p);
            if (stateName == null) {
                plugin.lang().send(p, "camp.not-found");
                return true;
            }

            String action = args.length >= 2 ? args[1].toLowerCase() : "info";

            if (action.equals("info")) {
                double balance = plugin.state().getBankBalance(stateName);
                String balanceText = plugin.state().formatMoney(balance);
                String taxText = plugin.state().formatMoney(plugin.state().getTaxAmount(stateName));
                long interval = Math.max(1L, plugin.getConfig().getLong("bank.tax.interval-minutes", 30));
                plugin.lang().send(p, "bank.info", Map.of(
                        "state", stateName,
                        "balance", balanceText,
                        "tax", taxText,
                        "interval", String.valueOf(interval)
                ));
                return true;
            }

            if (action.equals("deposit")) {
                if (args.length < 3) {
                    plugin.lang().send(p, "bank.deposit-usage");
                    return true;
                }
                double amount;
                try {
                    amount = Double.parseDouble(args[2]);
                } catch (NumberFormatException ex) {
                    plugin.lang().send(p, "general.invalid-number");
                    return true;
                }

                StateManager.BankActionResponse response = plugin.state().deposit(p, amount);
                switch (response.getResult()) {
                    case SUCCESS:
                        plugin.lang().send(p, "bank.deposit-success", Map.of(
                                "amount", plugin.state().formatMoney(response.getAmount()),
                                "balance", plugin.state().formatMoney(response.getNewBalance())
                        ));
                        break;
                    case INVALID_AMOUNT:
                        plugin.lang().send(p, "general.invalid-number");
                        break;
                    case INSUFFICIENT_PLAYER_FUNDS:
                        plugin.lang().send(p, "bank.deposit-insufficient");
                        break;
                    case NO_STATE:
                        plugin.lang().send(p, "camp.not-found");
                        break;
                    case NO_ECONOMY:
                        plugin.lang().send(p, "bank.no-economy");
                        break;
                    case DISABLED:
                        plugin.lang().send(p, "bank.disabled");
                        break;
                    case FAILED_TRANSACTION:
                        plugin.lang().send(p, "bank.transaction-failed");
                        break;
                    default:
                        plugin.lang().send(p, "general.unknown-error");
                        break;
                }
                return true;
            }

            if (action.equals("withdraw")) {
                if (!plugin.state().isCaptain(p)) {
                    plugin.lang().send(p, "bank.not-captain");
                    return true;
                }
                if (args.length < 3) {
                    plugin.lang().send(p, "bank.withdraw-usage");
                    return true;
                }

                double amount;
                try {
                    amount = Double.parseDouble(args[2]);
                } catch (NumberFormatException ex) {
                    plugin.lang().send(p, "general.invalid-number");
                    return true;
                }

                StateManager.BankActionResponse response = plugin.state().withdraw(p, amount);
                switch (response.getResult()) {
                    case SUCCESS:
                        plugin.lang().send(p, "bank.withdraw-success", Map.of(
                                "amount", plugin.state().formatMoney(response.getAmount()),
                                "balance", plugin.state().formatMoney(response.getNewBalance())
                        ));
                        break;
                    case INVALID_AMOUNT:
                        plugin.lang().send(p, "general.invalid-number");
                        break;
                    case INSUFFICIENT_BANK_FUNDS:
                        plugin.lang().send(p, "bank.withdraw-insufficient");
                        break;
                    case NO_STATE:
                        plugin.lang().send(p, "camp.not-found");
                        break;
                    case NO_ECONOMY:
                        plugin.lang().send(p, "bank.no-economy");
                        break;
                    case DISABLED:
                        plugin.lang().send(p, "bank.disabled");
                        break;
                    case FAILED_TRANSACTION:
                        plugin.lang().send(p, "bank.transaction-failed");
                        break;
                    case NOT_CAPTAIN:
                        plugin.lang().send(p, "bank.not-captain");
                        break;
                    default:
                        plugin.lang().send(p, "general.unknown-error");
                        break;
                }
                return true;
            }

            if (action.equals("log")) {
                if (!plugin.state().isCaptain(p)) {
                    plugin.lang().send(p, "bank.not-captain");
                    return true;
                }

                List<StateManager.BankTransaction> entries = plugin.state().getTransactions(stateName);
                plugin.lang().send(p, "bank.log-header");
                if (entries.isEmpty()) {
                    plugin.lang().send(p, "bank.log-empty");
                    return true;
                }

                String patternRaw = plugin.lang().messageOrDefault("bank.log-time-format", "HH:mm").replace("§", "");
                DateTimeFormatter formatter;
                try {
                    formatter = DateTimeFormatter.ofPattern(patternRaw);
                } catch (IllegalArgumentException ex) {
                    formatter = DateTimeFormatter.ofPattern("HH:mm");
                }

                for (StateManager.BankTransaction entry : entries) {
                    String actor = Optional.ofNullable(entry.getActor())
                            .map(uuid -> Bukkit.getOfflinePlayer(uuid).getName())
                            .orElse(null);
                    if (actor == null || actor.isEmpty()) {
                        actor = plugin.lang().messageOrDefault("bank.log-unknown", "未知");
                    }
                    String time = formatter.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(entry.getTimestamp()), ZoneId.systemDefault()));
                    String key;
                    switch (entry.getType()) {
                        case DEPOSIT:
                            key = "bank.log-entry-deposit";
                            break;
                        case WITHDRAW:
                            key = "bank.log-entry-withdraw";
                            break;
                        case TAX:
                            key = "bank.log-entry-tax";
                            break;
                        case EXPENSE:
                            key = "bank.log-entry-expense";
                            break;
                        default:
                            key = "bank.log-entry-expense";
                            break;
                    }
                    plugin.lang().send(p, key, Map.of(
                            "time", time,
                            "player", actor,
                            "amount", plugin.state().formatMoney(entry.getAmount()),
                            "balance", plugin.state().formatMoney(entry.getBalance())
                    ));
                }
                return true;
            }

            plugin.lang().send(p, "bank.usage");
            return true;
        }

        if (sub.equals("tax")) {
            if (!plugin.getConfig().getBoolean("bank.enabled", true)) {
                plugin.lang().send(p, "bank.disabled");
                return true;
            }
            if (!plugin.state().isCaptain(p)) {
                plugin.lang().send(p, "bank.not-captain");
                return true;
            }
            if (args.length < 2) {
                plugin.lang().send(p, "bank.tax-usage");
                return true;
            }

            double amount;
            try {
                amount = Double.parseDouble(args[1]);
            } catch (NumberFormatException ex) {
                plugin.lang().send(p, "bank.tax-invalid");
                return true;
            }

            StateManager.TaxUpdateResponse response = plugin.state().setTaxAmount(p, amount);
            switch (response.getStatus()) {
                case SUCCESS:
                    plugin.lang().send(p, "bank.tax-updated", Map.of(
                            "amount", plugin.state().formatMoney(response.getAmount())
                    ));
                    break;
                case NO_STATE:
                    plugin.lang().send(p, "camp.not-found");
                    break;
                case NOT_CAPTAIN:
                    plugin.lang().send(p, "bank.not-captain");
                    break;
                case INVALID_AMOUNT:
                    plugin.lang().send(p, "bank.tax-invalid");
                    break;
                case DISABLED:
                    plugin.lang().send(p, "bank.disabled");
                    break;
            }
            return true;
        }

        if (sub.equals("warcondemn")) {
            if (args.length < 2) {
                plugin.lang().send(p, "war.condemn-usage");
                return true;
            }

            String attackerState = plugin.state().getStateName(p);
            if (attackerState == null) {
                plugin.lang().send(p, "camp.not-found");
                return true;
            }

            if (!plugin.state().isCaptain(p)) {
                plugin.lang().send(p, "war.not-captain");
                return true;
            }

            String declaringState = plugin.state().getStateName(p);
            if (declaringState == null) {
                plugin.lang().send(p, "camp.not-found");
                return true;
            }

            StateManager.StateData attackerData = plugin.state().findState(declaringState);
            if (attackerData == null) {
                plugin.lang().send(p, "camp.not-found");
                return true;
            }

            if (!plugin.war().capitalHasFuel(declaringState)) {
                plugin.lang().send(p, "war.capital-no-fuel");
                return true;
            }

            StateManager.StateData target = plugin.state().findState(args[1]);
            if (target == null) {
                plugin.lang().send(p, "war.target-not-found", Map.of("state", args[1]));
                return true;
            }

            if (!plugin.state().isCaptainOnline(target)) {
                plugin.lang().send(p, "war.target-captain-offline", Map.of("state", target.name));
                return true;
            }

            WarManager.CondemnationResult result = plugin.war().condemnState(attackerState, target.name);
            switch (result.getStatus()) {
                case SUCCESS:
                    plugin.lang().send(p, "war.condemn-success", Map.of(
                            "state", target.name,
                            "time", plugin.war().formatDuration(result.getRemainingMs())
                    ));
                    Player targetCaptain = Bukkit.getPlayer(target.captain);
                    if (targetCaptain != null) {
                        plugin.lang().send(targetCaptain, "war.condemn-notify", Map.of("state", declaringState));
                    }
                    break;
                case INVALID_STATE:
                    plugin.lang().send(p, "war.target-not-found", Map.of("state", args[1]));
                    break;
                case SAME_SIDE:
                    plugin.lang().send(p, "war.same-state");
                    break;
                case ALREADY_AT_WAR:
                    plugin.lang().send(p, "war.declare-busy");
                    break;
                case ALREADY_CONDEMNED:
                    plugin.lang().send(p, "war.condemn-existing", Map.of(
                            "state", result.getExistingTarget() == null ? target.name : result.getExistingTarget()
                    ));
                    break;
                case ALREADY_CONDEMNED_OTHER:
                    plugin.lang().send(p, "war.condemn-other", Map.of(
                            "current", result.getExistingTarget(),
                            "requested", result.getTarget()
                    ));
                    break;
                case COOLDOWN:
                    plugin.lang().send(p, "war.condemn-cooldown", Map.of(
                            "time", plugin.war().formatDuration(result.getRemainingMs())
                    ));
                    break;
                case CIVIL_WAR_PENDING:
                    plugin.lang().send(p, "war.civilwar-pending-wait");
                    break;
            }
            return true;
        }

        if (sub.equals("surrender")) {
            if (args.length < 2 || (!args[1].equalsIgnoreCase("accept") && !args[1].equalsIgnoreCase("deny"))) {
                plugin.lang().send(p, "war.surrender-usage");
                return true;
            }

            String stateName = plugin.state().getStateName(p);
            if (stateName == null) {
                plugin.lang().send(p, "camp.not-found");
                return true;
            }
            if (!plugin.state().isCaptain(p)) {
                plugin.lang().send(p, "war.not-captain");
                return true;
            }

            boolean accept = args[1].equalsIgnoreCase("accept");
            WarManager.SurrenderResponseResult result = plugin.war().respondSurrender(stateName, accept);
            switch (result.getStatus()) {
                case NO_REQUEST -> plugin.lang().send(p, "war.surrender-none");
                case NOT_TARGET -> plugin.lang().send(p, "war.surrender-not-target", Map.of("state", result.getOpponent() == null ? "?" : result.getOpponent()));
                case EXPIRED -> plugin.lang().send(p, "war.surrender-expired");
                case DENIED -> {
                    plugin.lang().send(p, "war.surrender-denied");
                    String opponent = result.getOpponent();
                    StateManager.StateData opponentState = plugin.state().findState(opponent);
                    Player enemyCaptain = opponentState != null ? Bukkit.getPlayer(opponentState.captain) : null;
                    if (enemyCaptain != null) {
                        plugin.lang().send(enemyCaptain, "war.surrender-denied-notify", Map.of("state", stateName));
                    }
                }
                case ACCEPTED -> {
                    plugin.lang().send(p, "war.surrender-accepted");
                    String opponent = result.getOpponent();
                    StateManager.StateData opponentState = plugin.state().findState(opponent);
                    Player enemyCaptain = opponentState != null ? Bukkit.getPlayer(opponentState.captain) : null;
                    if (enemyCaptain != null) {
                        plugin.lang().send(enemyCaptain, "war.surrender-accepted-notify", Map.of("state", stateName));
                    }
                }
            }
            return true;
        }

        if (sub.equals("war")) {
            if (args.length < 2) {
                plugin.lang().send(p, "war.usage");
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("surrender")) {
                String stateName = plugin.state().getStateName(p);
                if (stateName == null) {
                    plugin.lang().send(p, "camp.not-found");
                    return true;
                }
                if (!plugin.state().isCaptain(p)) {
                    plugin.lang().send(p, "war.not-captain");
                    return true;
                }

                WarManager.SurrenderRequestResult result = plugin.war().requestSurrender(stateName);
                switch (result.getStatus()) {
                    case INVALID, NOT_AT_WAR -> plugin.lang().send(p, "war.surrender-not-war");
                    case NOT_PRIMARY -> plugin.lang().send(p, "war.surrender-not-primary");
                    case CAPTAIN_OFFLINE -> plugin.lang().send(p, "war.target-captain-offline", Map.of("state", result.getTarget() == null ? plugin.lang().messageOrDefault("placeholders.none", "无") : result.getTarget()));
                    case PENDING_SELF -> plugin.lang().send(p, "war.surrender-pending", Map.of(
                            "state", result.getTarget() == null ? plugin.lang().messageOrDefault("placeholders.none", "无") : result.getTarget(),
                            "time", plugin.war().formatDuration(result.getRemainingMs())
                    ));
                    case PENDING_OTHER -> plugin.lang().send(p, "war.surrender-other", Map.of(
                            "state", result.getTarget() == null ? plugin.lang().messageOrDefault("placeholders.none", "无") : result.getTarget()
                    ));
                    case SUCCESS -> {
                        String target = result.getTarget();
                        Map<String, String> vars = new HashMap<>();
                        vars.put("state", target == null ? plugin.lang().messageOrDefault("placeholders.none", "无") : target);
                        vars.put("time", plugin.war().formatDuration(result.getRemainingMs()));
                        plugin.lang().send(p, "war.surrender-requested", vars);

                        StateManager.StateData targetState = target == null ? null : plugin.state().findState(target);
                        Player targetCaptain = targetState != null ? Bukkit.getPlayer(targetState.captain) : null;
                        if (targetCaptain != null) {
                            plugin.lang().send(targetCaptain, "war.surrender-notify", Map.of(
                                    "state", stateName,
                                    "time", plugin.war().formatDuration(result.getRemainingMs())
                            ));
                        }
                    }
                }
                return true;
            }

            if (!plugin.state().isCaptain(p)) {
                plugin.lang().send(p, "war.not-captain");
                return true;
            }

            String declaringState = plugin.state().getStateName(p);
            if (declaringState == null) {
                plugin.lang().send(p, "camp.not-found");
                return true;
            }

            StateManager.StateData attackerData = plugin.state().findState(declaringState);
            if (attackerData == null) {
                plugin.lang().send(p, "camp.not-found");
                return true;
            }

            if (!plugin.war().capitalHasFuel(declaringState)) {
                plugin.lang().send(p, "war.capital-no-fuel");
                return true;
            }

            StateManager.StateData target = plugin.state().findState(args[1]);
            if (target == null) {
                plugin.lang().send(p, "war.target-not-found", Map.of("state", args[1]));
                return true;
            }

            if (!plugin.state().isCaptainOnline(target)) {
                plugin.lang().send(p, "war.target-captain-offline", Map.of("state", target.name));
                return true;
            }

            WarManager.WarStartResult result = plugin.war().startWar(declaringState, target.name, false);
            switch (result) {
                case SUCCESS:
                    plugin.lang().send(p, "war.declare-confirm", Map.of("state", target.name));
                    break;
                case SAME_SIDE:
                    plugin.lang().send(p, "war.same-state");
                    break;
                case ALREADY_AT_WAR:
                    plugin.lang().send(p, "war.already-war");
                    break;
                case COOLDOWN:
                    plugin.lang().send(p, "war.cooldown-active", Map.of(
                            "time", plugin.war().formatDuration(plugin.war().getWarCooldownRemaining(declaringState))
                    ));
                    break;
                case ATTACKER_BUSY:
                    plugin.lang().send(p, "war.declare-busy");
                    break;
                case NO_CONDEMNATION:
                    plugin.lang().send(p, "war.condemn-required");
                    break;
                case CONDEMNATION_PENDING:
                    plugin.lang().send(p, "war.condemn-wait", Map.of(
                            "time", plugin.war().formatDuration(plugin.war().getCondemnationRemaining(declaringState))
                    ));
                    break;
                case CONDEMNATION_WRONG_TARGET:
                    String targetName = plugin.war().getCondemnationTarget(declaringState);
                    plugin.lang().send(p, "war.condemn-wrong-target", Map.of(
                            "state", targetName == null ? args[1] : targetName
                    ));
                    break;
                case REQUIREMENTS:
                    plugin.lang().send(p, "war.declare-requirements", Map.of(
                            "members_required", String.valueOf(plugin.war().getRequiredMembersForWar()),
                            "sectors_required", String.valueOf(plugin.war().getRequiredSectorsForWar()),
                            "members_current", String.valueOf(attackerData.members.size()),
                            "sectors_current", String.valueOf(attackerData.sectors.size())
                    ));
                    break;
                case CIVIL_WAR_PENDING:
                    plugin.lang().send(p, "war.civilwar-pending-wait");
                    break;
                case INVALID_STATE:
                    plugin.lang().send(p, "war.target-not-found", Map.of("state", target.name));
                    break;
                default:
                    plugin.lang().send(p, "general.unknown-error");
                    break;
            }
            return true;
        }
        if (sub.equals("civilwar")) {
            StateManager.CivilWarResult result = plugin.state().initiateCivilWar(p);
            if (result.getStatus() != StateManager.CivilWarStatus.SUCCESS) {
                return true;
            }

            Map<String, String> vars = new HashMap<>();
            vars.put("state", result.getRebel());
            vars.put("origin", result.getOrigin());
            String pendingSector = result.getSector();
            vars.put("sector", pendingSector == null
                    ? plugin.lang().messageOrDefault("placeholders.none", "无")
                    : pendingSector);
            plugin.lang().send(p, "war.civilwar-place-required", vars);
            return true;
        }

        plugin.lang().send(p, "camp.unknown");
        return true;
    }

}
