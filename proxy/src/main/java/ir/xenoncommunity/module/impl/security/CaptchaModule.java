package ir.xenoncommunity.module.impl.security;

import ir.xenoncommunity.XenonCore;
import ir.xenoncommunity.module.ModuleBase;
import ir.xenoncommunity.annotations.ModuleInfo;
import net.md_5.bungee.protocol.packet.MapData;
import ir.xenoncommunity.utils.Configuration;
import ir.xenoncommunity.utils.MapPalette;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import java.util.concurrent.ScheduledFuture;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.protocol.packet.Chat;
import net.md_5.bungee.protocol.packet.KeepAlive;
import net.md_5.bungee.protocol.packet.Login;
import net.md_5.bungee.protocol.packet.PlayerPositionAndLook;
import net.md_5.bungee.protocol.ProtocolConstants;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ModuleInfo(name = "Captcha", version = 1.0, description = "Map-based captcha with pre-verification")
public class CaptchaModule extends ModuleBase implements Listener {

    private final Map<UUID, CaptchaSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> verifiedPlayers = new ConcurrentHashMap<>();
    private final File verifiedFile = new File("verified_players.csv");

    @Override
    public void onInit() {
        if (!getConfig().getModules().getCaptcha_module().isEnabled()) return;
        loadVerifiedPlayers();
        XenonCore.instance.getBungeeInstance().getPluginManager().registerListener(null, this);

        getTaskManager().repeatingTask(this::cleanupSessions, 1, 1, TimeUnit.MINUTES);
    }

    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        ProxiedPlayer player = event.getPlayer();
        if (isVerified(player.getUniqueId())) return;

        sessions.put(player.getUniqueId(), new CaptchaSession(player));
        startPreVerification(player);
    }

    @EventHandler
    public void onServerConnect(ServerConnectEvent event) {
        ProxiedPlayer player = event.getPlayer();
        CaptchaSession session = sessions.get(player.getUniqueId());
        
        if (session != null && !session.verified) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChat(ChatEvent event) {
        if (!(event.getSender() instanceof ProxiedPlayer)) return;
        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        CaptchaSession session = sessions.get(player.getUniqueId());

        if (session != null && session.state == State.CAPTCHA) {
            event.setCancelled(true);
            String message = event.getMessage().trim();
            if (message.equalsIgnoreCase(session.code)) {
                handleSuccess(player);
            } else {
                handleFailure(player);
            }
        }
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        cleanupSession(event.getPlayer().getUniqueId());
    }

    private void cleanupSession(UUID uuid) {
        CaptchaSession session = sessions.remove(uuid);
        if (session != null && session.task != null) {
            session.task.cancel(true);
        }
    }

    private void startPreVerification(ProxiedPlayer player) {
        CaptchaSession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        session.state = State.PRE_VERIFY;
        int duration = getConfig().getModules().getCaptcha_module().getPre_verify_duration();
        
        session.task = getTaskManager().repeatingTask(() -> {
            if (player.isConnected()) {
                player.unsafe().sendPacket(new KeepAlive(System.currentTimeMillis()));
            }

            if (session.elapsed >= duration) {
                if (player.getPing() > getConfig().getModules().getCaptcha_module().getMax_ping()) {
                    player.disconnect(ChatColor.translateAlternateColorCodes('&', 
                        getConfig().getModules().getCaptcha_module().getMessages().getPing_too_high()
                        .replace("%ping%", String.valueOf(player.getPing()))
                        .replace("%max%", String.valueOf(getConfig().getModules().getCaptcha_module().getMax_ping()))));
                    cleanupSession(player.getUniqueId());
                    return;
                }
                session.task.cancel(true);
                showCaptcha(player);
                return;
            }

            session.elapsed++;
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                getConfig().getModules().getCaptcha_module().getMessages().getPre_verify()
                .replace("%time%", String.valueOf(duration - session.elapsed))));
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void showCaptcha(ProxiedPlayer player) {
        CaptchaSession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        session.state = State.CAPTCHA;
        session.code = generateCode();
        byte[] mapData = generateMapData(session.code);

        Login login = new Login();
        login.setEntityId(-1);
        login.setHardcore(false);
        login.setGameMode((short) 1);
        login.setPreviousGameMode((short) -1);
        login.setWorldNames(Collections.singleton("minecraft:overworld"));
        
        int version = player.getPendingConnection().getVersion();
        if (version >= ProtocolConstants.MINECRAFT_1_16) {
            login.setDimension("minecraft:overworld");
        } else {
            login.setDimension(0);
        }

        login.setWorldName("minecraft:overworld");
        login.setDifficulty((short) 1);
        login.setMaxPlayers(1);
        login.setLevelType("default");
        login.setViewDistance(2);
        login.setSimulationDistance(2);
        
        player.unsafe().sendPacket(login);

        // Send Position to prevent "Downloading terrain" hang
        player.unsafe().sendPacket(new PlayerPositionAndLook(0, 64, 0, 0f, 0f, (byte) 0, 0, false));

        MapData map = new MapData();
        map.setMapId(0);
        map.setScale((byte) 4);
        map.setTrackingPosition(false);
        map.setLocked(true);
        map.setColumns((byte) 128);
        map.setRows((byte) 128);
        map.setX((byte) 0);
        map.setZ((byte) 0);
        map.setData(mapData);

        player.unsafe().sendPacket(map);

        player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
            getConfig().getModules().getCaptcha_module().getMessages().getInstructions()));

        // Start KeepAlive task for Captcha phase
        session.task = getTaskManager().repeatingTask(() -> {
            if (player.isConnected()) {
                player.unsafe().sendPacket(new KeepAlive(System.currentTimeMillis()));
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void handleSuccess(ProxiedPlayer player) {
        CaptchaSession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        session.verified = true;
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
            getConfig().getModules().getCaptcha_module().getMessages().getSuccess()));
        
        long expiry = System.currentTimeMillis() + (TimeUnit.HOURS.toMillis(getConfig().getModules().getCaptcha_module().getVerification_duration()));
        verifiedPlayers.put(player.getUniqueId(), expiry);
        saveVerifiedPlayer(player.getUniqueId(), expiry);
        
        cleanupSession(player.getUniqueId());

        player.connect(XenonCore.instance.getBungeeInstance().getServerInfo(player.getPendingConnection().getListener().getDefaultServer()));
    }

    private void handleFailure(ProxiedPlayer player) {
        CaptchaSession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        session.attempts++;
        if (session.attempts >= 3) {
            player.disconnect(ChatColor.translateAlternateColorCodes('&', 
                getConfig().getModules().getCaptcha_module().getMessages().getToo_many_attempts()));
            sessions.remove(player.getUniqueId());
        } else {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                getConfig().getModules().getCaptcha_module().getMessages().getInvalid_code()
                .replace("%attempts%", String.valueOf(3 - session.attempts))));
        }
    }

    private String generateCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 5; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private byte[] generateMapData(String code) {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 128, 128);
        
        Random random = new Random();
        int difficulty = getConfig().getModules().getCaptcha_module().getDifficulty();
        g.setColor(Color.LIGHT_GRAY);
        for (int i = 0; i < 50 * difficulty; i++) {
            g.fillOval(random.nextInt(128), random.nextInt(128), 2, 2);
        }

        g.setFont(new Font("Arial", Font.BOLD, 24));
        g.setColor(Color.BLACK);
        
        FontMetrics fm = g.getFontMetrics();
        int x = (128 - fm.stringWidth(code)) / 2;
        int y = (128 - fm.getHeight()) / 2 + fm.getAscent();
        
        char[] chars = code.toCharArray();
        int currentX = x;
        for (char c : chars) {
            g.rotate(Math.toRadians(random.nextInt(20) - 10), currentX, y);
            g.drawString(String.valueOf(c), currentX, y);
            g.rotate(-Math.toRadians(random.nextInt(20) - 10), currentX, y);
            currentX += fm.charWidth(c) + 2;
        }

        g.dispose();

        byte[] data = new byte[128 * 128];
        for (int i = 0; i < 128; i++) {
            for (int j = 0; j < 128; j++) {
                data[j * 128 + i] = MapPalette.getColor(new Color(image.getRGB(i, j)));
            }
        }
        return data;
    }

    private boolean isVerified(UUID uuid) {
        Long expiry = verifiedPlayers.get(uuid);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            verifiedPlayers.remove(uuid);
            return false;
        }
        return true;
    }

    private void loadVerifiedPlayers() {
        if (!verifiedFile.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(verifiedFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 2) {
                    verifiedPlayers.put(UUID.fromString(parts[0]), Long.parseLong(parts[1]));
                }
            }
        } catch (Exception e) {
            XenonCore.instance.logdebugerror("Failed to load verified players: " + e.getMessage());
        }
    }

    private void saveVerifiedPlayer(UUID uuid, long expiry) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(verifiedFile, true))) {
            writer.write(uuid.toString() + "," + expiry);
            writer.newLine();
        } catch (Exception e) {
            XenonCore.instance.logdebugerror("Failed to save verified player: " + e.getMessage());
        }
    }

    private void cleanupSessions() {
        long current = System.currentTimeMillis();
        verifiedPlayers.entrySet().removeIf(entry -> current > entry.getValue());
    }

    private enum State {
        PRE_VERIFY, CAPTCHA
    }

    private static class CaptchaSession {
        final ProxiedPlayer player;
        State state;
        String code;
        int attempts = 0;
        int elapsed = 0;
        boolean verified = false;
        ScheduledFuture<?> task;

        CaptchaSession(ProxiedPlayer player) {
            this.player = player;
        }
    }
}
