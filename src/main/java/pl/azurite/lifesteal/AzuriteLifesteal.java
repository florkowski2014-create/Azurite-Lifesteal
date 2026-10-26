package pl.azurite.lifesteal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AzuriteLifesteal extends JavaPlugin implements Listener, TabExecutor {

    private static final String ADMIN_PERM = "azurite.lf.admin";
    private static final String HEART = "serce";
    private static final String BOOK = "ksiazkaodrodzenia";

    private static final class PData {
        String name;
        int hearts;
        long banUntil;

        PData(String name, int hearts, long banUntil) {
            this.name = name;
            this.hearts = hearts;
            this.banUntil = banUntil;
        }
    }

    private final Map<UUID, PData> data = new ConcurrentHashMap<>();
    private NamespacedKey itemKey;
    private File dataFile;
    private int startHearts;
    private int maxHearts;
    private int banHours;
    private int reviveHearts;
    private boolean anyDeath;

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        startHearts = Math.max(1, getConfig().getInt("start-hearts", 10));
        maxHearts = Math.max(1, getConfig().getInt("max-hearts", 20));
        banHours = Math.max(1, getConfig().getInt("ban-hours", 24));
        reviveHearts = Math.max(1, getConfig().getInt("revive-hearts", 3));
        anyDeath = getConfig().getBoolean("lose-heart-on-any-death", true);

        itemKey = new NamespacedKey(this, "lf_item");
        dataFile = new File(getDataFolder(), "data.yml");
        load();

        getServer().getPluginManager().registerEvents(this, this);
        for (String name : List.of("wyplac", "odrodz", "azurite-lf")) {
            if (getCommand(name) != null) {
                getCommand(name).setExecutor(this);
                getCommand(name).setTabCompleter(this);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            get(p);
            applyHealth(p);
        }
    }

    @Override
    public void onDisable() {
        save();
    }

    // ------------------------------------------------------------ storage

    private void load() {
        data.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = y.getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection c = root.getConfigurationSection(key);
                if (c == null) continue;
                data.put(id, new PData(c.getString("name", "?"), c.getInt("hearts", startHearts), c.getLong("banUntil", 0L)));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, PData> en : data.entrySet()) {
            String p = "players." + en.getKey();
            y.set(p + ".name", en.getValue().name);
            y.set(p + ".hearts", en.getValue().hearts);
            y.set(p + ".banUntil", en.getValue().banUntil);
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Nie udało się zapisać data.yml: " + e.getMessage());
        }
    }

    private PData get(Player p) {
        PData d = data.computeIfAbsent(p.getUniqueId(), k -> new PData(p.getName(), startHearts, 0L));
        d.name = p.getName();
        return d;
    }

    private void applyHealth(Player p) {
        PData d = get(p);
        if (d.hearts > maxHearts) d.hearts = maxHearts;
        if (d.hearts < 1) return;
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;
        double max = d.hearts * 2.0;
        attr.setBaseValue(max);
        if (!p.isDead() && p.getHealth() > max) p.setHealth(max);
    }

    // ------------------------------------------------------------ items

    private ItemStack makeItem(String type, int amount) {
        ItemStack it;
        ItemMeta m;
        if (type.equals(HEART)) {
            it = new ItemStack(Material.HEART_OF_THE_SEA, amount);
            m = it.getItemMeta();
            m.displayName(Component.text("Serce", NamedTextColor.RED, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(Component.text("Kliknij prawym, aby dodać sobie serce.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
        } else {
            it = new ItemStack(Material.BOOK, amount);
            m = it.getItemMeta();
            m.displayName(Component.text("Księga Odrodzenia", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(Component.text("Trzymaj w ręce i wpisz /odrodz <nick>,", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("aby przywrócić zbanowanego gracza.", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
        }
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, type);
        it.setItemMeta(m);
        return it;
    }

    private String typeOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    private void give(Player p, ItemStack it) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        left.values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
    }

    private static void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }

    private static String left(long ms) {
        long m = Math.max(1, ms / 60000);
        return (m / 60) + "h " + (m % 60) + "min";
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        PData d = data.get(e.getUniqueId());
        long now = System.currentTimeMillis();
        if (d != null && d.banUntil > now) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    Component.text("Straciłeś wszystkie serca.\nWrócisz za " + left(d.banUntil - now) + ".", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PData d = get(p);
        if (d.hearts <= 0) {
            d.hearts = reviveHearts;
            d.banUntil = 0L;
            save();
            msg(p, "Wracasz do gry z " + d.hearts + " sercami.", NamedTextColor.GREEN);
        }
        applyHealth(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> applyHealth(p));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (!anyDeath && killer == null) return;

        PData vd = get(victim);
        vd.hearts = Math.max(0, vd.hearts - 1);

        if (killer != null && !killer.equals(victim)) {
            PData kd = get(killer);
            if (kd.hearts < maxHearts) {
                kd.hearts++;
                applyHealth(killer);
                msg(killer, "+1 serce! Masz ich: " + kd.hearts, NamedTextColor.GREEN);
            } else {
                give(killer, makeItem(HEART, 1));
                msg(killer, "Masz maksymalną liczbę serc, dostajesz serce jako przedmiot.", NamedTextColor.GREEN);
            }
        }

        if (vd.hearts <= 0) {
            vd.banUntil = System.currentTimeMillis() + banHours * 3_600_000L;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (victim.isOnline()) {
                    victim.kick(Component.text("Straciłeś ostatnie serce. Ban na " + banHours + "h.", NamedTextColor.RED));
                }
            }, 40L);
        } else {
            msg(victim, "Straciłeś serce. Zostało Ci: " + vd.hearts, NamedTextColor.RED);
            Bukkit.getScheduler().runTask(this, () -> applyHealth(victim));
        }
        save();
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null) return;
        String type = typeOf(it);
        if (type == null) return;
        e.setCancelled(true);
        if (!type.equals(HEART)) return;

        Player p = e.getPlayer();
        PData d = get(p);
        if (d.hearts >= maxHearts) {
            msg(p, "Masz już maksymalną liczbę serc (" + maxHearts + ").", NamedTextColor.RED);
            return;
        }
        it.setAmount(it.getAmount() - 1);
        d.hearts++;
        applyHealth(p);
        save();
        msg(p, "+1 serce! Masz ich: " + d.hearts, NamedTextColor.GREEN);
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (typeOf(it) != null) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        switch (c.getName().toLowerCase()) {
            case "wyplac":
                return cmdWithdraw(s, a);
            case "odrodz":
                return cmdRevive(s, a);
            case "azurite-lf":
                return cmdAdmin(s, a);
            default:
                return false;
        }
    }

    private boolean cmdWithdraw(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            msg(s, "Tylko dla gracza.", NamedTextColor.RED);
            return true;
        }
        int n = 1;
        if (a.length > 0) {
            try {
                n = Integer.parseInt(a[0]);
            } catch (NumberFormatException ex) {
                n = 0;
            }
        }
        if (n < 1 || n > 64) {
            msg(s, "Ilość musi być od 1 do 64.", NamedTextColor.RED);
            return true;
        }
        PData d = get(p);
        if (d.hearts - n < 1) {
            msg(s, "Musisz zostawić sobie co najmniej 1 serce.", NamedTextColor.RED);
            return true;
        }
        d.hearts -= n;
        applyHealth(p);
        give(p, makeItem(HEART, n));
        save();
        msg(s, "Wypłacono " + n + " serc. Zostało Ci: " + d.hearts, NamedTextColor.GREEN);
        return true;
    }

    private boolean cmdRevive(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            msg(s, "Tylko dla gracza.", NamedTextColor.RED);
            return true;
        }
        if (a.length < 1) {
            msg(s, "/odrodz <nick>", NamedTextColor.RED);
            return true;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!BOOK.equals(typeOf(hand))) {
            msg(s, "Musisz trzymać w ręce Księgę Odrodzenia.", NamedTextColor.RED);
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(a[0]);
        PData td = target == null ? null : data.get(target.getUniqueId());
        if (td == null || td.banUntil <= System.currentTimeMillis()) {
            msg(s, "Ten gracz nie jest zbanowany za utratę serc.", NamedTextColor.RED);
            return true;
        }
        td.banUntil = 0L;
        td.hearts = reviveHearts;
        hand.setAmount(hand.getAmount() - 1);
        save();
        Bukkit.broadcast(Component.text(p.getName() + " odrodził gracza " + td.name + "!", NamedTextColor.LIGHT_PURPLE));
        return true;
    }

    private boolean cmdAdmin(CommandSender s, String[] a) {
        if (!s.hasPermission(ADMIN_PERM)) {
            msg(s, "Brak uprawnień.", NamedTextColor.RED);
            return true;
        }
        if (a.length < 2 || !a[0].equalsIgnoreCase("give")) {
            msg(s, "/azurite-lf give <serce|ksiazkaodrodzenia> [1-64] [gracz]", NamedTextColor.GRAY);
            return true;
        }
        String type = a[1].toLowerCase();
        if (!type.equals(HEART) && !type.equals(BOOK)) {
            msg(s, "Dostępne: serce, ksiazkaodrodzenia", NamedTextColor.RED);
            return true;
        }
        int n = 1;
        if (a.length > 2) {
            try {
                n = Integer.parseInt(a[2]);
            } catch (NumberFormatException ex) {
                n = 0;
            }
        }
        if (n < 1 || n > 64) {
            msg(s, "Ilość musi być od 1 do 64.", NamedTextColor.RED);
            return true;
        }
        Player target;
        if (a.length > 3) {
            target = Bukkit.getPlayerExact(a[3]);
        } else {
            target = s instanceof Player p ? p : null;
        }
        if (target == null) {
            msg(s, "Podaj gracza online.", NamedTextColor.RED);
            return true;
        }
        give(target, makeItem(type, n));
        msg(s, "Dano " + n + "x " + type + " graczowi " + target.getName() + ".", NamedTextColor.GREEN);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        String last = a[a.length - 1].toLowerCase();
        List<String> out = new ArrayList<>();
        switch (c.getName().toLowerCase()) {
            case "wyplac" -> {
                if (a.length == 1) out.addAll(List.of("1", "5", "10"));
            }
            case "odrodz" -> {
                if (a.length == 1) {
                    long now = System.currentTimeMillis();
                    for (PData d : data.values()) if (d.banUntil > now) out.add(d.name);
                }
            }
            case "azurite-lf" -> {
                if (!s.hasPermission(ADMIN_PERM)) return List.of();
                if (a.length == 1) out.add("give");
                else if (a.length == 2) out.addAll(List.of(HEART, BOOK));
                else if (a.length == 3) out.addAll(List.of("1", "16", "64"));
                else if (a.length == 4) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            }
            default -> {
            }
        }
        return out.stream().filter(x -> x.toLowerCase().startsWith(last)).toList();
    }
          }
