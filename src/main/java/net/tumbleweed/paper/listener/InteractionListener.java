package net.tumbleweed.paper.listener;

import net.kyori.adventure.text.Component;
import net.tumbleweed.paper.Tumbleweed;
import net.tumbleweed.paper.TumbleweedManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 玩家交互:
 *  - 右击手持命名牌 -> 命名风滚草并设为持久 (原版 interact)
 *  - 左键攻击 -> 自然死亡流程 (伤害事件原样放行,由 MythicMobs 死亡事件结算;
 *    Pig 血量 1,攻击即致死,无需自定义伤害处理)
 */
public class InteractionListener implements Listener {

    private final TumbleweedManager manager;

    public InteractionListener(TumbleweedManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEntityEvent event) {
        Tumbleweed tw = manager.get(event.getRightClicked());
        if (tw == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack stack = event.getHand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();

        // 原版:手持带自定义名的命名牌 -> 命名并设为持久
        if (stack.getType() == Material.NAME_TAG) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                Component name = meta.displayName();
                tw.entity().customName(name);
                tw.setPersistent(true);
                stack.setAmount(stack.getAmount() - 1);
                event.setCancelled(true);
            }
        }
    }
}
