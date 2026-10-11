package dev.blockpicker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

public final class BlockPicker extends JavaPlugin implements TabExecutor, Listener {

    private static final int PAGE_SIZE = 45; // 5 rows of blocks, bottom row = controls

    private final List<Material> allowed = new ArrayList<>();
    private int maxAmount = 64;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadBlocks();
        Objects.requireNonNull(getCommand("block")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Loaded " + allowed.size() + " building blocks.");
    }

    private void loadBlocks() {
        reloadConfig();
        allowed.clear();
        maxAmount = Math.max(1, Math.min(getConfig().getInt("max-amount", 64), 2304));

        Set<Material> excluded = EnumSet.noneOf(Material.class);
        for (String s : getConfig().getStringList("excluded-materials")) {
            Material m = Material.matchMaterial(s);
            if (m != null) excluded.add(m);
            else getLogger().warning("Unknown material in excluded-materials: " + s);
        }
        List<String> patterns = getConfig().getStringList("excluded-name-contains")
                .stream().map(String::toUpperCase).collect(Collectors.toList());

        outer:
        for (Material m : Material.values()) {
            if (m.isLegacy() || m.isAir() || !m.isBlock() || !m.isItem()) continue;
            if (excluded.contains(m)) continue;
            String name = m.name();
            for (String p : patterns) {
                if (name.contains(p)) continue outer;
            }
            allowed.add(m);
        }
        allowed.sort(Comparator.comparing(Material::name));
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!player.hasPermission("blockpicker.use")) {
            player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload") && player.hasPermission("blockpicker.reload")) {
            loadBlocks();
            player.sendMessage(Component.text("Reloaded. " + allowed.size() + " blocks available.", NamedTextColor.GREEN));
            return true;
        }

        if (args.length == 0) {
            openPage(player, 0);
            return true;
        }

        Material m = Material.matchMaterial(args[0]);
        if (m == null || !allowed.contains(m)) {
            player.sendMessage(Component.text("'" + args[0] + "' is not an available building block.", NamedTextColor.RED));
            return true;
        }
        int amount = 64;
        if (args.length >= 2) {
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("Amount must be a number.", NamedTextColor.RED));
                return true;
            }
        }
        amount = Math.max(1, Math.min(amount, maxAmount));
        give(player, m, amount);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return allowed.stream()
                    .map(m -> m.name().toLowerCase())
                    .filter(n -> n.startsWith(prefix))
                    .limit(100)
                    .collect(Collectors.toList());
        }
        if (args.length == 2) return List.of("1", "16", "32", "64");
        return List.of();
    }

    private void give(Player player, Material m, int amount) {
        // Split into stacks so amounts above 64 work
        int max = m.getMaxStackSize();
        int left = amount;
        boolean dropped = false;
        while (left > 0) {
            int n = Math.min(left, max);
            Map<Integer, ItemStack> over = player.getInventory().addItem(new ItemStack(m, n));
            if (!over.isEmpty()) {
                dropped = true;
                over.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
            }
            left -= n;
        }
        player.sendMessage(Component.text("Gave you " + amount + "x " + pretty(m) + (dropped ? " (some dropped, inventory full)" : ""),
                NamedTextColor.GREEN));
    }

    // -------------------------------------------------------------------- GUI

    private static final class PickerHolder implements InventoryHolder {
        final int page;
        Inventory inv;
        PickerHolder(int page) { this.page = page; }
        @Override public @NotNull Inventory getInventory() { return inv; }
    }

    private void openPage(Player player, int page) {
        int pages = Math.max(1, (int) Math.ceil(allowed.size() / (double) PAGE_SIZE));
        page = Math.max(0, Math.min(page, pages - 1));

        PickerHolder holder = new PickerHolder(page);
        Inventory inv = org.bukkit.Bukkit.createInventory(holder, 54,
                Component.text("Blocks - Page " + (page + 1) + "/" + pages));
        holder.inv = inv;

        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < allowed.size(); i++) {
            Material m = allowed.get(start + i);
            ItemStack it = new ItemStack(m);
            ItemMeta meta = it.getItemMeta();
            meta.lore(List.of(
                    Component.text("Left-click: 1", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Shift-click: full stack", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            it.setItemMeta(meta);
            inv.setItem(i, it);
        }

        if (page > 0) inv.setItem(45, control(Material.ARROW, "Previous page"));
        inv.setItem(49, control(Material.BARRIER, "Close"));
        if (page < pages - 1) inv.setItem(53, control(Material.ARROW, "Next page"));

        player.openInventory(inv);
    }

    private ItemStack control(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof PickerHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        int slot = e.getRawSlot();
        if (slot == 45 && holder.page > 0) {
            openPage(player, holder.page - 1);
        } else if (slot == 53) {
            openPage(player, holder.page + 1);
        } else if (slot == 49) {
            player.closeInventory();
        } else if (slot >= 0 && slot < PAGE_SIZE) {
            int idx = holder.page * PAGE_SIZE + slot;
            if (idx >= allowed.size()) return;
            Material m = allowed.get(idx);
            int amount = e.isShiftClick() ? m.getMaxStackSize() : 1;
            give(player, m, amount);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PickerHolder) e.setCancelled(true);
    }

    private static String pretty(Material m) {
        String[] parts = m.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
