package com.chridev.sftpBackup;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.logging.Level;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class SftpBackup extends JavaPlugin {

    private FileConfiguration messagesConfig;
    private File messagesFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveDefaultMessages();
        loadMessages();
        getLogger().info(getMessage("pluginEnabled"));
    }

    @Override
    public void onDisable() {
        getLogger().info(getMessage("pluginDisabled"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("serverbackup")) {
            if (!sender.hasPermission("chridev.serverbackup")) {
                sender.sendMessage(getMessage("noPermission"));
                return true;
            }

            getLogger().info(getMessage("backupStart"));
            new Thread(() -> {
                try {
                    File folderToUpload = new File("."); // server folder
                    sendFolderSFTP(folderToUpload);
                    getLogger().info(getMessage("backupComplete"));
                    sender.sendMessage(getMessage("backupComplete"));
                } catch (Exception e) {
                    getLogger().log(Level.SEVERE, getMessage("backupFailed"), e);
                    sender.sendMessage(getMessage("backupFailed"));
                }
            }).start();
            return true;
        }
        return false;
    }

    private void sendFolderSFTP(File folder) throws Exception {
        String host = getConfig().getString("host", "localhost");
        int port = getConfig().getInt("port", 22);
        String user = getConfig().getString("user", "user");
        String pass = getConfig().getString("password", "");
        String remoteDir = getConfig().getString("remoteDir", "/");

        JSch jsch = new JSch();
        Session session = jsch.getSession(user, host, port);
        session.setPassword(pass);
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);
        session.connect(15000); // timeout 15 sec

        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(10000); // timeout 10 sec

        // Se la cartella remota non esiste, la crea
        try {
            channel.cd(remoteDir);
        } catch (Exception e) {
            createRemoteDir(channel, remoteDir);
            channel.cd(remoteDir);
        }

        uploadFolderRecursive(channel, folder, remoteDir);

        channel.disconnect();
        session.disconnect();
    }

    private void createRemoteDir(ChannelSftp channel, String remoteDir) throws Exception {
        String[] folders = remoteDir.split("/");
        String path = "";
        for (String folder : folders) {
            if (folder.isEmpty()) continue;
            path += "/" + folder;
            try {
                channel.cd(path);
            } catch (Exception e) {
                channel.mkdir(path);
            }
        }
    }

    private void uploadFolderRecursive(ChannelSftp channel, File folder, String remotePath) throws Exception {
        if (!folder.isDirectory()) return;

        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                String newRemotePath = remotePath.endsWith("/") ? remotePath + file.getName() : remotePath + "/" + file.getName();
                try { channel.mkdir(newRemotePath); } catch (Exception ignored) {}
                uploadFolderRecursive(channel, file, newRemotePath);
            } else {
                try (FileInputStream fis = new FileInputStream(file)) {
                    channel.put(fis, remotePath + "/" + file.getName());
                }
            }
        }
    }

    // --- messages.yml helpers ---
    private void saveDefaultMessages() {
        if (!getDataFolder().exists()) {
            boolean created = getDataFolder().mkdirs();
            if (!created) {
                getLogger().warning("Could not create plugin data folder!");
            }
        }
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) saveResource("messages.yml", false);
    }

    private void loadMessages() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    private String getMessage(String key) {
        if (messagesConfig == null) return key;
        return messagesConfig.getString(key, key);
    }
}
