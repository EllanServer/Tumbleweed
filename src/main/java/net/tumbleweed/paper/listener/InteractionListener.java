package net.tumbleweed.paper.listener;

import net.tumbleweed.paper.Tumbleweed;
import net.tumbleweed.paper.TumbleweedManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 玩家交互:
 *  - 右击手持命名牌 -> 命名风滚草并设为持久 (原版 interact)
 *  - 左键攻击 -> 自然死亡流程 (伤害事件原样放行,由 MythicMobs 死亡事件结算)
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
        if (stack.getType() == Material.NAME_TAG && stack.getItemMeta() != null
                && stack.getItemMeta().hasDisplayName()) {
            tw.entity().setCustomName(stack.getItemMeta().getDisplayName());
            tw.setPersistent(true);
            stack.setAmount(stack.getAmount() - 1);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDamage(EntityDamageByEntityEvent event) {
        // 玩家攻击风滚草:原版任何伤害即死 (Pig 1 点血),由 MM 死亡事件结算掉落
        if (manager.isTumbleweed(event.getEntity()) && event.getDamager() instanceof Player) {
            // 原版 skipAttackInteraction:攻击瞬间触发 hurt
            // 默认行为已足够:Pig 血量 1,攻击直接致死
        }
    }
}
