package net.minelink.ctplus.compat.v1_16_R3;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_16_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_16_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Pair;

import net.minecraft.server.v1_16_R3.EntityPlayer;
import net.minecraft.server.v1_16_R3.EnumItemSlot;
import net.minecraft.server.v1_16_R3.FoodMetaData;
import net.minecraft.server.v1_16_R3.ItemStack;
import net.minecraft.server.v1_16_R3.MinecraftServer;
import net.minecraft.server.v1_16_R3.NBTCompressedStreamTools;
import net.minecraft.server.v1_16_R3.NBTTagCompound;
import net.minecraft.server.v1_16_R3.NBTTagList;
import net.minecraft.server.v1_16_R3.Packet;
import net.minecraft.server.v1_16_R3.PacketPlayOutEntityEquipment;
import net.minecraft.server.v1_16_R3.PacketPlayOutPlayerInfo;
import net.minecraft.server.v1_16_R3.PacketPlayOutPlayerInfo.EnumPlayerInfoAction;
import net.minecraft.server.v1_16_R3.WorldNBTStorage;
import net.minecraft.server.v1_16_R3.WorldServer;
import net.minelink.ctplus.compat.api.NpcIdentity;
import net.minelink.ctplus.compat.api.NpcPlayerHelper;

public final class NpcPlayerHelperImpl implements NpcPlayerHelper {

    @Override
    public Player spawn(Player player) {
        NpcPlayer npcPlayer = NpcPlayer.valueOf(player);
        WorldServer worldServer = ((CraftWorld) player.getWorld()).getHandle();
        Location l = player.getLocation();

        npcPlayer.spawnIn(worldServer);
        npcPlayer.setPositionRotation(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        npcPlayer.playerInteractManager.a(worldServer);
        npcPlayer.invulnerableTicks = 0;

        for (Object o : MinecraftServer.getServer().getPlayerList().players) {
            if (!(o instanceof EntityPlayer) || o instanceof NpcPlayer) continue;

            PacketPlayOutPlayerInfo packet = new PacketPlayOutPlayerInfo(EnumPlayerInfoAction.ADD_PLAYER, npcPlayer);
            ((EntityPlayer) o).playerConnection.sendPacket(packet);
        }

        worldServer.addEntity(npcPlayer);

        return npcPlayer.getBukkitEntity();
    }

    @Override
    public void despawn(Player player) {
        EntityPlayer entity = ((CraftPlayer) player).getHandle();

        if (!(entity instanceof NpcPlayer)) {
            throw new IllegalArgumentException();
        }

        for (Object o : MinecraftServer.getServer().getPlayerList().players) {
            if (!(o instanceof EntityPlayer) || o instanceof NpcPlayer) continue;

            PacketPlayOutPlayerInfo packet = new PacketPlayOutPlayerInfo(EnumPlayerInfoAction.REMOVE_PLAYER, entity);
            ((EntityPlayer) o).playerConnection.sendPacket(packet);
        }

        WorldServer worldServer = MinecraftServer.getServer().getWorldServer(entity.world.getDimensionKey());
        worldServer.removeEntity(entity);
    }

    @Override
    public boolean isNpc(Player player) {
        return ((CraftPlayer) player).getHandle() instanceof NpcPlayer;
    }

    @Override
    public NpcIdentity getIdentity(Player player) {
        if (!isNpc(player)) {
            throw new IllegalArgumentException();
        }

        return ((NpcPlayer) ((CraftPlayer) player).getHandle()).getNpcIdentity();
    }

    @Override
    public void updateEquipment(Player player) {
        EntityPlayer entity = ((CraftPlayer) player).getHandle();

        if (!(entity instanceof NpcPlayer)) {
            throw new IllegalArgumentException();
        }

        for (EnumItemSlot slot : EnumItemSlot.values()) {
            ItemStack item = entity.getEquipment(slot);
            if (item == null) continue;

            // Set the attribute for this equipment to consider armor values and enchantments
            // Actually getAttributeMap().a() is used with the previous item, to clear the Attributes
            entity.getAttributeMap().a(item.a(slot));
            entity.getAttributeMap().b(item.a(slot));

            // This is also called by super.tick(), but the flag this.bx is not public
            List<Pair<EnumItemSlot, ItemStack>> list = Lists.newArrayList();
            list.add(Pair.of(slot, item));
            Packet packet = new PacketPlayOutEntityEquipment(entity.getId(), list);
            entity.getWorldServer().getChunkProvider().broadcast(entity, packet);
        }
    }

    @Override
    public void syncOffline(Player player) {
        EntityPlayer entity = ((CraftPlayer) player).getHandle();

        if (!(entity instanceof NpcPlayer)) {
            throw new IllegalArgumentException();
        }

        NpcPlayer npcPlayer = (NpcPlayer) entity;
        NpcIdentity identity = npcPlayer.getNpcIdentity();
        Player p = Bukkit.getPlayer(identity.getId());
        if (p != null && p.isOnline()) return;

        WorldNBTStorage worldStorage = (WorldNBTStorage) ((CraftWorld) Bukkit.getWorlds().get(0)).getHandle().getMinecraftServer().worldNBTStorage;
        NBTTagCompound playerNbt = worldStorage.getPlayerData(identity.getId().toString());
        if (playerNbt == null) return;

        // foodTickTimer is now private in 1.8.3 -- still private in 1.12
        Field foodTickTimerField;
        int foodTickTimer;

        try {
            foodTickTimerField = FoodMetaData.class.getDeclaredField("foodTickTimer");
            foodTickTimerField.setAccessible(true);
            foodTickTimer = foodTickTimerField.getInt(entity.getFoodData());
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        playerNbt.setShort("Air", (short) entity.getAirTicks());
        // Health is now just a float; fractional is not stored separately. (1.12)
        playerNbt.setShort("HurtTime", (short) entity.hurtTicks);
        playerNbt.setInt("HurtByTimestamp", entity.hurtTimestamp);
        playerNbt.setFloat("Health", entity.getHealth());
        playerNbt.setFloat("AbsorptionAmount", entity.getAbsorptionHearts());
        playerNbt.setInt("XpTotal", entity.expTotal);
        playerNbt.setInt("foodLevel", entity.getFoodData().foodLevel);
        playerNbt.setInt("foodTickTimer", foodTickTimer);
        playerNbt.setFloat("foodSaturationLevel", entity.getFoodData().saturationLevel);
        playerNbt.setFloat("foodExhaustionLevel", entity.getFoodData().exhaustionLevel);
        playerNbt.setShort("Fire", (short) entity.fireTicks);
        playerNbt.set("Inventory", npcPlayer.inventory.a(new NBTTagList()));

        File file1 = new File(worldStorage.getPlayerDir(), identity.getId().toString() + ".dat.tmp");
        File file2 = new File(worldStorage.getPlayerDir(), identity.getId().toString() + ".dat");

        try {
            NBTCompressedStreamTools.a(playerNbt, new FileOutputStream(file1));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save player data for " + identity.getName(), e);
        }

        if ((!file2.exists() || file2.delete()) && !file1.renameTo(file2)) {
            throw new RuntimeException("Failed to save player data for " + identity.getName());
        }
    }

    @Override
    public void createPlayerList(Player player) {
        EntityPlayer p = ((CraftPlayer) player).getHandle();

        for (WorldServer worldServer : MinecraftServer.getServer().getWorlds()) {
            for (Object o : worldServer.getPlayers()) {
                if (!(o instanceof NpcPlayer)) continue;

                NpcPlayer npcPlayer = (NpcPlayer) o;
                PacketPlayOutPlayerInfo packet = new PacketPlayOutPlayerInfo(EnumPlayerInfoAction.ADD_PLAYER, npcPlayer);
                p.playerConnection.sendPacket(packet);
            }
        }
    }

    @Override
    public void removePlayerList(Player player) {
        EntityPlayer p = ((CraftPlayer) player).getHandle();

        for (WorldServer worldServer : MinecraftServer.getServer().getWorlds()) {
            for (Object o : worldServer.getPlayers()) {
                if (!(o instanceof NpcPlayer)) continue;

                NpcPlayer npcPlayer = (NpcPlayer) o;
                PacketPlayOutPlayerInfo packet = new PacketPlayOutPlayerInfo(EnumPlayerInfoAction.REMOVE_PLAYER, npcPlayer);
                p.playerConnection.sendPacket(packet);
            }
        }
    }

}
