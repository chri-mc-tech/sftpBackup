package com.chridev.sftpBackup;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;
import java.util.logging.Level;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

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
        if (!command.getName().equalsIgnoreCase("serverbackup")) return false;

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("chridev.serverbackup.reload")) {
                sender.sendMessage(getMessage("noPermission"));
                return true;
            }
            reloadConfig();
            loadMessages();
            sender.sendMessage(getMessage("pluginReloaded"));
            getLogger().info(getMessage("pluginReloaded"));
            return true;
        }

        if (!sender.hasPermission("chridev.serverbackup")) {
            sender.sendMessage(getMessage("noPermission"));
            return true;
        }

        getLogger().info(getMessage("backupStart"));
        new Thread(() -> {
            File backupFile = null;
            try {
                File serverFolder = new File(".");
                File backupFolder = new File(getDataFolder(), "backup");
                if (!backupFolder.exists() && !backupFolder.mkdirs()) {
                    getLogger().warning("Impossible to create backup folder.");
                }

                backupFile = new File(backupFolder, "server_backup.tar");
                tarFolder(serverFolder, backupFile);

                getLogger().info("TAR file created, starting SFTP upload...");

                Thread.sleep(2000); // Delay prima del trasferimento

                sendFileSFTP(backupFile);

                getLogger().info(getMessage("backupComplete"));
                sender.sendMessage(getMessage("backupComplete"));
            } catch (IOException | InterruptedException e) {
                getLogger().log(Level.SEVERE, getMessage("backupFailed"), e);
                sender.sendMessage(getMessage("backupFailed"));
            } finally {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

                if (backupFile != null && backupFile.exists()) {
                    if (!backupFile.delete()) {
                        getLogger().warning("Cannot delete temporary server_backup.tar.");
                    } else {
                        getLogger().info("Temporary TAR deleted successfully.");
                    }
                }
            }
        }).start();
        return true;
    }

    private void tarFolder(File sourceFolder, File tarFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(tarFile);
             TarArchiveOutputStream tos = new TarArchiveOutputStream(fos)) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX); // supporto nomi lunghi
            tarFolderRecursive(sourceFolder, sourceFolder.getName(), tos);
        }
    }

    private void tarFolderRecursive(File folder, String parentName, TarArchiveOutputStream tos) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            // Salta il backup già esistente e link simbolici
            if (file.getName().equalsIgnoreCase("server_backup.tar") || Files.isSymbolicLink(file.toPath())) continue;

            String entryName = parentName + "/" + file.getName();
            TarArchiveEntry entry = new TarArchiveEntry(file, entryName);

            try {
                tos.putArchiveEntry(entry);

                if (file.isFile()) {
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[8192]; // buffer più grande
                        int len;
                        while ((len = fis.read(buffer)) != -1) {
                            tos.write(buffer, 0, len);
                        }
                    } catch (IOException e) {
                        getLogger().warning("Skipped file in TAR due to error: " + file.getAbsolutePath());
                    }
                } else if (file.isDirectory()) {
                    tarFolderRecursive(file, entryName, tos);
                }

                tos.closeArchiveEntry();
            } catch (Exception e) {
                getLogger().warning("Failed to add " + file.getAbsolutePath() + " to TAR: " + e.getMessage());
            }
        }
    }

    private void sendFileSFTP(File file) throws IOException {
        String host = getConfig().getString("host", "localhost");
        int port = getConfig().getInt("port", 22);
        String user = getConfig().getString("user", "user");
        String pass = getConfig().getString("password", "");
        String remoteDir = getConfig().getString("remoteDir", "/");

        JSch jsch = new JSch();
        Session session = null;
        ChannelSftp channel = null;

        try {
            session = jsch.getSession(user, host, port);
            session.setPassword(pass);
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect();

            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect();

            try {
                channel.cd(remoteDir);
            } catch (Exception e) {
                channel.mkdir(remoteDir);
                channel.cd(remoteDir);
            }

            try (FileInputStream fis = new FileInputStream(file)) {
                channel.put(fis, file.getName());
            }

            getLogger().info("SFTP upload finished successfully.");

        } catch (Exception e) {
            throw new IOException("Error during SFTP upload", e);
        } finally {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            if (channel != null && channel.isConnected()) channel.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    private void saveDefaultMessages() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Cannot create plugin data folder.");
        }
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
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
