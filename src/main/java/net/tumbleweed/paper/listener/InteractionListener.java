package net.tumbleweed.paper.listener;

import net.momirealms.craftengine.bukkit.api.event.FurnitureHitEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.tumbleweed.paper.Tumbleweed;
import net.tumbleweed.paper.TumbleweedManager;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 玩家交互 (全部由 craft-engine 家具托管):
 *  - 右击家具手持命名牌 -> 命名风滚草并设为持久 (原版 interact)
 *  - 打击家具 -> 对载体实体造成 1 点伤害 (原版攻击即死,由 MythicMobs 死亡事件结算掉落)
 *
 * 载体实体 (Pig) 隐形且不可被玩家交互/攻击:下面的 Bukkit 事件监听仅做
 * 防御性拦截,把玩家对 Pig 的交互路由到 CE 家具 (防止绕过家具 hitbox 直接
 * 打/点 Pig);非玩家伤害 (骷髅箭等) 不受影响,仍由 MM 结算掉落。
 */
public class InteractionListener implements Listener {

    private final TumbleweedManager manager;

    public InteractionListener(TumbleweedManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(FurnitureInteractEvent event) {
        Tumbleweed tw = manager.getByFurniture(event.furniture());
        if (tw == null) {
            return;
        }
        Player player = event.player();
        ItemStack stack = player.getInventory().getItemInMainHand();

        // 原版:手持带自定义名的命名牌 -> 命名并设为持久
        if (stack.getType() == Material.NAME_TAG && stack.getItemMeta() != null
                && stack.getItemMeta().hasDisplayName()) {
            tw.entity().setCustomName(stack.getItemMeta().getDisplayName());
            tw.setPersistent(true);
            // 家具元素同步显示名字 (玩家看到的是 CE 家具)
            Entity base = tw.furniture().baseEntity();
            if (base != null) {
                base.setCustomName(stack.getItemMeta().getDisplayName());
            }
            stack.setAmount(stack.getAmount() - 1);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onHit(FurnitureHitEvent event) {
        Tumbleweed tw = manager.getByFurniture(event.furniture());
        if (tw == null) {
            return;
        }
        // 原版 skipAttackInteraction:攻击瞬间触发 hurt,Pig 1 点血即死,
        // 掉落与击杀判定由 MythicMobs 死亡事件结算
        ((org.bukkit.entity.LivingEntity) tw.entity()).damage(1.0, event.player());
    }

    // ============ 防御性拦截:玩家不得绕过 CE 家具直接与 Pig 交互 ============

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamagePig(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player && manager.get(event.getEntity()) != null) {
            // 玩家对风滚草的攻击一律取消:玩家应打击 CE 家具 (FurnitureHitEvent)
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractPig(PlayerInteractEntityEvent event) {
        if (manager.get(event.getRightClicked()) != null) {
            // 玩家对风滚草的右击一律取消:玩家应右击 CE 家具 (FurnitureInteractEvent)
            event.setCancelled(true);
        }
    }
}
