package net.voidflame.installer;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class VoidFlameInstaller extends JavaPlugin {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private Path root, dataDir, backupRoot, stagingDir;
    private volatile String lastOperation = "No operation performed yet.";

    @Override public void onEnable() {
        saveDefaultConfig();
        root = getServer().getWorldContainer().toPath().toAbsolutePath().normalize();
        dataDir = getDataFolder().toPath().toAbsolutePath().normalize();
        backupRoot = dataDir.resolve("backups");
        stagingDir = dataDir.resolve("staging");
        try { Files.createDirectories(backupRoot); Files.createDirectories(stagingDir); }
        catch (IOException e) { getLogger().severe("Could not prepare installer folders: " + e.getMessage()); }
        getLogger().info("Enabled. Payload repository: " + repoBase());
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) { help(sender); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                sender.sendMessage(msg("&7Repository: &f" + repoBase()));
                sender.sendMessage(msg("&7Server root: &f" + root));
                sender.sendMessage(msg("&7Last operation: &f" + lastOperation));
            }
            case "check" -> runAsync(sender, false);
            case "update" -> runAsync(sender, true);
            case "rollback" -> rollback(sender);
            default -> help(sender);
        }
        return true;
    }

    private void runAsync(CommandSender sender, boolean apply) {
        sender.sendMessage(msg(apply ? "&eDownloading and preparing manifest updates..." : "&eChecking repository manifest..."));
        CompletableFuture.runAsync(() -> {
            try {
                List<Entry> entries = loadManifest();
                if (entries.isEmpty()) {
                    throw new IllegalStateException("manifest.yml contains no files. Add built SMP plugin JARs/configs and SHA-256 hashes before running /vfi check or /vfi update.");
                }
                List<String> report = new ArrayList<>();
                int changed = 0;
                for (Entry entry : entries) {
                    Path target = safeTarget(entry);
                    if (entry.type.equals("world-zip")) {
                        if (Files.exists(target)) report.add("&7SKIP world exists: &f" + entry.target);
                        else { report.add("&aNEW world: &f" + entry.target); changed++; }
                        continue;
                    }
                    boolean exists = Files.isRegularFile(target);
                    boolean same = exists && !entry.sha256.isBlank() && sha256(target).equalsIgnoreCase(entry.sha256);
                    if (same) report.add("&aOK: &f" + entry.target);
                    else { report.add((exists ? "&eUPDATE: &f" : "&aADD: &f") + entry.target); changed++; }
                }
                if (!apply) {
                    int count = changed;
                    getServer().getScheduler().runTask(this, () -> {
                        sender.sendMessage(msg("&7Manifest entries: &f" + entries.size() + " &8| &7Pending: &f" + count));
                        report.stream().limit(18).forEach(line -> sender.sendMessage(msg(line)));
                        if (report.size() > 18) sender.sendMessage(msg("&7...and " + (report.size() - 18) + " more."));
                        lastOperation = "Check completed at " + Instant.now() + "; pending=" + count;
                    });
                    return;
                }
                String tx = "tx-" + System.currentTimeMillis();
                Path txDir = backupRoot.resolve(tx);
                Files.createDirectories(txDir);
                List<String> installed = new ArrayList<>();
                List<String> warnings = new ArrayList<>();
                int applied = 0;
                for (Entry entry : entries) {
                    Path source = download(entry);
                    if (entry.type.equals("world-zip")) {
                        Path worldDir = safeTarget(entry);
                        if (Files.exists(worldDir)) { warnings.add("Skipped existing world " + entry.target); continue; }
                        Files.createDirectories(worldDir);
                        try { extractZip(source, worldDir); installed.add("WORLD_CREATED:" + worldDir); applied++; }
                        catch (Exception e) { deleteTree(worldDir); throw e; }
                        continue;
                    }
                    Path target = safeTarget(entry);
                    if (Files.isRegularFile(target) && !entry.sha256.isBlank() && sha256(target).equalsIgnoreCase(entry.sha256)) continue;
                    Files.createDirectories(target.getParent());
                    if (getConfig().getBoolean("updates.backups-enabled", true) && Files.exists(target)) {
                        Path backup = txDir.resolve(root.relativize(target).toString());
                        Files.createDirectories(backup.getParent());
                        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                    Path temp = target.resolveSibling(target.getFileName() + ".vfi-tmp");
                    Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
                    try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                    catch (IOException moveError) {
                        try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
                        catch (IOException second) {
                            if (target.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                                Path staged = stagingDir.resolve(target.getFileName());
                                Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
                                warnings.add("Could not replace active/locked JAR; staged at " + staged + ". Stop Paper and move it into plugins/ before restart.");
                                Files.deleteIfExists(temp);
                                continue;
                            }
                            throw second;
                        }
                    }
                    installed.add(root.relativize(target).toString());
                    applied++;
                }
                pruneBackups();
                int count = applied;
                getServer().getScheduler().runTask(this, () -> {
                    sender.sendMessage(msg("&aUpdate finished. Files installed: &f" + count));
                    sender.sendMessage(msg("&7Restart Paper to load updated JARs and configs."));
                    for (String warning : warnings) sender.sendMessage(msg("&eWarning: &f" + warning));
                    lastOperation = "Update completed at " + Instant.now() + "; installed=" + count + "; transaction=" + tx;
                });
            } catch (Exception e) {
                getLogger().warning("Installer operation failed: " + e.getMessage());
                getServer().getScheduler().runTask(this, () -> {
                    sender.sendMessage(msg("&cOperation failed: &f" + e.getMessage()));
                    lastOperation = "Failed at " + Instant.now() + ": " + e.getMessage();
                });
            }
        });
    }

    private List<Entry> loadManifest() throws Exception {
        String url = repoBase() + "/" + getConfig().getString("repository.manifest", "manifest.yml");
        Path temp = dataDir.resolve("manifest-download.yml");
        downloadUrl(url, temp);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(temp.toFile());
        List<Map<?, ?>> raw = yaml.getMapList("files");
        List<Entry> entries = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (Map<?, ?> map : raw) {
            String source = Objects.toString(map.get("source"), "").trim();
            String target = Objects.toString(map.get("target"), "").trim();
            String type = Objects.toString(map.get("type"), "file").trim().toLowerCase(Locale.ROOT);
            String hash = Objects.toString(map.get("sha256"), "").trim();
            if (source.isBlank() || target.isBlank()) throw new IOException("Manifest entry is missing source or target.");
            if (!source.matches("[A-Za-z0-9._/-]+") || source.contains("..") || source.startsWith("/")) throw new IOException("Unsafe manifest source path: " + source);
            if (!type.equals("file") && !type.equals("world-zip")) throw new IOException("Unsupported manifest type: " + type);
            if (!hash.isBlank() && !hash.matches("(?i)[0-9a-f]{64}")) throw new IOException("Invalid SHA-256 for " + source);
            Entry entry = new Entry(source, target, type, hash);
            Path safe = safeTarget(entry);
            String key = safe.toString().toLowerCase(Locale.ROOT);
            if (!targets.add(key)) throw new IOException("Duplicate target in manifest: " + target);
            if (hash.isBlank() && !getConfig().getBoolean("updates.allow-unverified-files", true))
                throw new IOException("Missing SHA-256 for " + source + ". Add a digest to manifest.yml.");
            if (type.equals("world-zip") && !getConfig().getBoolean("updates.allow-world-archives", true))
                throw new IOException("World archives are disabled in config.yml.");
            entries.add(entry);
        }
        return entries;
    }

    private Path safeTarget(Entry e) throws IOException {
        Path target;
        if (e.type.equals("world-zip")) {
            if (!e.target.matches("[A-Za-z0-9_-]{1,64}")) throw new IOException("World target must be a simple folder name: " + e.target);
            target = root.resolve(e.target);
        } else {
            Path rel = Paths.get(e.target);
            if (rel.isAbsolute() || e.target.contains("..") || e.target.startsWith("/") || e.target.startsWith("\\")) throw new IOException("Unsafe target path: " + e.target);
            String normalized = rel.toString().replace('\\', '/');
            if (!(normalized.startsWith("plugins/") || normalized.startsWith("config/") || normalized.startsWith("configs/") || normalized.equals("paper-global.yml") || normalized.equals("spigot.yml") || normalized.equals("bukkit.yml")))
                throw new IOException("Target is outside allowed server plugin/config paths: " + e.target);
            target = root.resolve(rel).normalize();
        }
        if (!target.normalize().startsWith(root)) throw new IOException("Target escapes server root: " + e.target);
        if (target.startsWith(dataDir)) throw new IOException("Target cannot point inside installer data.");
        return target.normalize();
    }

    private Path download(Entry entry) throws Exception {
        Path dir = dataDir.resolve("downloads"); Files.createDirectories(dir);
        Path file = dir.resolve(UUID.randomUUID() + ".bin");
        downloadUrl(repoBase() + "/" + entry.source, file);
        long max = Math.max(1, getConfig().getLong("updates.max-file-size-mib", 512)) * 1024L * 1024L;
        if (Files.size(file) > max) { Files.deleteIfExists(file); throw new IOException("File exceeds configured size limit: " + entry.source); }
        if (!entry.sha256.isBlank() && !sha256(file).equalsIgnoreCase(entry.sha256)) {
            Files.deleteIfExists(file); throw new IOException("SHA-256 mismatch for " + entry.source);
        }
        if (entry.sha256.isBlank()) getLogger().warning("No SHA-256 configured for " + entry.source + "; file was not integrity-verified.");
        return file;
    }

    private void downloadUrl(String url, Path destination) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).header("User-Agent", "VoidFlame-Installer/1.0").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) { response.body().close(); throw new IOException("GitHub returned HTTP " + response.statusCode() + " for " + url); }
        try (InputStream in = response.body()) { Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING); }
    }

    private void extractZip(Path zip, Path destination) throws Exception {
        Path base = destination.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = in.getNextEntry()) != null) {
                Path out = base.resolve(entry.getName()).normalize();
                if (!out.startsWith(base)) throw new IOException("Unsafe path inside world ZIP: " + entry.getName());
                if (entry.isDirectory()) Files.createDirectories(out);
                else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW)) {
                        int n; while ((n = in.read(buffer)) > 0) os.write(buffer, 0, n);
                    }
                }
                in.closeEntry();
            }
        }
    }

    private void rollback(CommandSender sender) {
        CompletableFuture.runAsync(() -> {
            try (var stream = Files.list(backupRoot)) {
                Path latest = stream.filter(Files::isDirectory).max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
                if (latest == null) throw new IOException("No backup transaction found.");
                int restored = 0;
                try (var walk = Files.walk(latest)) {
                    for (Path backup : walk.filter(Files::isRegularFile).toList()) {
                        Path target = root.resolve(latest.relativize(backup).toString()).normalize();
                        if (!target.startsWith(root)) throw new IOException("Unsafe backup path.");
                        Files.createDirectories(target.getParent());
                        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                        restored++;
                    }
                }
                int count = restored;
                getServer().getScheduler().runTask(this, () -> {
                    sender.sendMessage(msg("&aRollback complete. Restored files: &f" + count));
                    sender.sendMessage(msg("&7Restart Paper if any plugin/config files were restored."));
                    lastOperation = "Rollback completed at " + Instant.now() + "; restored=" + count;
                });
            } catch (Exception e) {
                getServer().getScheduler().runTask(this, () -> sender.sendMessage(msg("&cRollback failed: &f" + e.getMessage())));
            }
        });
    }

    private void pruneBackups() throws IOException {
        int keep = getConfig().getInt("updates.backup-retention", 10);
        if (keep <= 0) return;
        try (var stream = Files.list(backupRoot)) {
            List<Path> dirs = stream.filter(Files::isDirectory).sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).toList();
            for (int i = keep; i < dirs.size(); i++) deleteTree(dirs.get(i));
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String repoBase() {
        String owner = getConfig().getString("repository.owner", "alialghamdi6068");
        String name = getConfig().getString("repository.name", "VoidFlame-Installer");
        String branch = getConfig().getString("repository.branch", "main");
        return "https://raw.githubusercontent.com/" + owner + "/" + name + "/" + branch;
    }

    private String msg(String text) {
        String prefix = getConfig().getString("messages.prefix", "&8[&5VoidFlame Installer&8] &r");
        return ChatColor.translateAlternateColorCodes('&', prefix + text);
    }

    private void help(CommandSender sender) {
        sender.sendMessage(msg("&d/vfi check &7- preview manifest changes"));
        sender.sendMessage(msg("&d/vfi update &7- download, verify, backup and install"));
        sender.sendMessage(msg("&d/vfi status &7- show repository and last operation"));
        sender.sendMessage(msg("&d/vfi rollback &7- restore files from latest backup"));
    }

    private record Entry(String source, String target, String type, String sha256) {}
}
