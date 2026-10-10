import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Digital Archaeologist: a read-only console tool for exploring files.
 */
public class DigitalArchaeologist {
    private record FileEntry(Path path, String name, String extension, long size, String modified) {}

    private static String humanSize(long size) {
        double value = size;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }

    private static List<FileEntry> scanFolder(Path root) {
        List<FileEntry> entries = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        for (Path part : root.relativize(path)) {
                            String name = part.toString();
                            if (name.equals(".git") || name.equals("node_modules")
                                    || name.equals("__pycache__")) return false;
                        }
                        return true;
                    })
                    .forEach(path -> {
                        try {
                            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                            String filename = path.getFileName().toString();
                            int dot = filename.lastIndexOf('.');
                            String extension = dot > 0 ? filename.substring(dot).toLowerCase() : "[no extension]";
                            entries.add(new FileEntry(path, filename, extension, attrs.size(),
                                    attrs.lastModifiedTime().toInstant().toString()));
                        } catch (IOException ignored) {
                            // Continue past files the current user cannot read.
                        }
                    });
        } catch (IOException e) {
            System.out.println("Could not scan folder: " + e.getMessage());
        }
        return entries;
    }

    private static void showOverview(Path root, List<FileEntry> entries) {
        Map<String, Integer> types = new TreeMap<>();
        long total = 0;
        for (FileEntry entry : entries) {
            types.merge(entry.extension(), 1, Integer::sum);
            total += entry.size();
        }
        System.out.println("\n  Folder: " + root);
        System.out.println("  Files: " + entries.size() + "   Total size: " + humanSize(total));
        System.out.println("\n  File types:");
        types.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(12)
                .forEach(entry -> System.out.printf("    %-18s %5d%n", entry.getKey(), entry.getValue()));
        System.out.println("\n  Largest files:");
        entries.stream().sorted(Comparator.comparingLong(FileEntry::size).reversed()).limit(5)
                .forEach(entry -> System.out.printf("    %10s  %s%n", humanSize(entry.size()), entry.path()));
        System.out.println();
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void findDuplicates(List<FileEntry> entries) {
        Map<Long, List<FileEntry>> bySize = new LinkedHashMap<>();
        for (FileEntry entry : entries) bySize.computeIfAbsent(entry.size(), ignored -> new ArrayList<>()).add(entry);
        Map<String, List<Path>> byHash = new LinkedHashMap<>();
        bySize.values().stream().filter(group -> group.size() > 1).flatMap(List::stream).forEach(entry -> {
            try {
                byHash.computeIfAbsent(sha256(entry.path()), ignored -> new ArrayList<>()).add(entry.path());
            } catch (IOException | NoSuchAlgorithmException ignored) {
                // Skip files that become unavailable during the scan.
            }
        });
        List<List<Path>> duplicateGroups = byHash.values().stream().filter(group -> group.size() > 1).toList();
        if (duplicateGroups.isEmpty()) {
            System.out.println("No exact duplicates found.\n");
            return;
        }
        System.out.println("\nFound " + duplicateGroups.size() + " duplicate group(s):");
        for (int i = 0; i < duplicateGroups.size(); i++) {
            System.out.println("\n  Group " + (i + 1) + ":");
            duplicateGroups.get(i).forEach(path -> System.out.println("    " + path));
        }
        System.out.println();
    }

    private static String jsonEscape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static void exportJson(Path root, List<FileEntry> entries) {
        Path destination = Paths.get("archaeology_report.json").toAbsolutePath();
        long total = entries.stream().mapToLong(FileEntry::size).sum();
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"folder\": \"").append(jsonEscape(root.toString())).append("\",\n")
                .append("  \"scanned_at\": \"").append(Instant.now()).append("\",\n")
                .append("  \"file_count\": ").append(entries.size()).append(",\n")
                .append("  \"total_bytes\": ").append(total).append(",\n  \"files\": [");
        for (int i = 0; i < entries.size(); i++) {
            FileEntry entry = entries.get(i);
            json.append(i == 0 ? "\n" : ",\n")
                    .append("    {\"path\": \"").append(jsonEscape(entry.path().toString()))
                    .append("\", \"name\": \"").append(jsonEscape(entry.name()))
                    .append("\", \"extension\": \"").append(jsonEscape(entry.extension()))
                    .append("\", \"size\": ").append(entry.size())
                    .append(", \"modified\": \"").append(jsonEscape(entry.modified())).append("\"}");
        }
        if (!entries.isEmpty()) json.append('\n');
        json.append("  ]\n}\n");
        try {
            Files.writeString(destination, json.toString());
            System.out.println("Saved inventory: " + destination + "\n");
        } catch (IOException e) {
            System.out.println("Could not save report: " + e.getMessage());
        }
    }

    private static Path askFolder(Scanner input) {
        System.out.print("Folder path (Enter = current folder): ");
        String raw = input.nextLine().trim().replaceAll("^\"|\"$", "");
        Path folder = Paths.get(raw.isEmpty() ? "." : raw).toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) {
            System.out.println("That folder does not exist.\n");
            return null;
        }
        return folder;
    }

    public static void main(String[] args) {
        System.out.println("\n  ╔══════════════════════════════════════╗");
        System.out.println("  ║       DIGITAL ARCHAEOLOGIST          ║");
        System.out.println("  ║   Read-only file history explorer    ║");
        System.out.println("  ╚══════════════════════════════════════╝");
        try (Scanner input = new Scanner(System.in)) {
            while (true) {
                System.out.println("\n  [1] Scan a folder and show its overview");
                System.out.println("  [2] Find exact duplicate files (SHA-256)");
                System.out.println("  [3] Export a folder inventory as JSON");
                System.out.println("  [4] Quit");
                System.out.print("\n  Choose: ");
                String choice = input.nextLine().trim();
                if (choice.equals("4")) {
                    System.out.println("See you, archaeologist!\n");
                    return;
                }
                if (!List.of("1", "2", "3").contains(choice)) {
                    System.out.println("Please choose 1, 2, 3, or 4.\n");
                    continue;
                }
                Path root = askFolder(input);
                if (root == null) continue;
                System.out.println("Scanning...");
                List<FileEntry> entries = scanFolder(root);
                switch (choice) {
                    case "1" -> showOverview(root, entries);
                    case "2" -> findDuplicates(entries);
                    case "3" -> exportJson(root, entries);
                    default -> { }
                }
            }
        }
    }
}
