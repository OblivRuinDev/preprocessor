package dev.oblivruin.preprocessor.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** File system and pattern helpers shared by the tasks. */
public final class FileUtils {
    /** Number of leading bytes scanned for a zero byte to detect binary files. */
    public static final int BINARY_SCAN_BYTES = 8192;

    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<String, Pattern>();

    private FileUtils() {
    }

    /** @return all regular files below the given directory, sorted for deterministic order */
    public static List<Path> walkFiles(Path base) throws IOException {
        try (Stream<Path> stream = Files.walk(base)) {
            return stream.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
    }

    /**
     * Reads at most {@code maxBytes} bytes from the start of the file (fewer at the end
     * of a shorter file). Used for the binary probe so large binary files never need to
     * be read completely.
     */
    public static byte[] readHead(Path file, int maxBytes) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[maxBytes];
            int read = readFully(in, buffer);
            return read == maxBytes ? buffer : Arrays.copyOf(buffer, read);
        }
    }

    /** @return {@code true} if the data looks binary (contains a zero byte) */
    public static boolean isBinary(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code true} if the data starts with a UTF-8 byte order mark */
    public static boolean hasUtf8Bom(byte[] data) {
        return data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF;
    }

    /** Prepends a UTF-8 byte order mark. */
    public static byte[] withBom(byte[] data) {
        byte[] result = new byte[data.length + 3];
        result[0] = (byte) 0xEF;
        result[1] = (byte) 0xBB;
        result[2] = (byte) 0xBF;
        System.arraycopy(data, 0, result, 3, data.length);
        return result;
    }

    /**
     * Writes the data only if the target does not already contain exactly these bytes.
     * Missing parent directories are created.
     */
    public static void writeIfChanged(Path target, byte[] data) throws IOException {
        if (Files.exists(target) && Arrays.equals(Files.readAllBytes(target), data)) {
            return;
        }
        createParentDirectories(target);
        Files.write(target, data);
    }

    /**
     * Copies {@code source} to {@code target} only when the target does not already
     * hold exactly the same content. Both files are compared as streams, so arbitrarily
     * large files (jar resources, textures, ...) are handled with constant memory;
     * identical files are never rewritten. Missing parent directories are created.
     */
    public static void copyIfChanged(Path source, Path target) throws IOException {
        if (Files.exists(target)
                && Files.size(source) == Files.size(target)
                && sameBytes(source, target)) {
            return;
        }
        createParentDirectories(target);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean sameBytes(Path a, Path b) throws IOException {
        try (InputStream inA = Files.newInputStream(a); InputStream inB = Files.newInputStream(b)) {
            byte[] bufferA = new byte[8192];
            byte[] bufferB = new byte[8192];
            while (true) {
                int lenA = readFully(inA, bufferA);
                int lenB = readFully(inB, bufferB);
                if (lenA != lenB) {
                    return false;
                }
                if (lenA == 0) {
                    return true;
                }
                for (int i = 0; i < lenA; i++) {
                    if (bufferA[i] != bufferB[i]) {
                        return false;
                    }
                }
            }
        }
    }

    /** Reads up to {@code buffer.length} bytes, blocking until the buffer is full or the stream ends. */
    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return read;
    }

    private static void createParentDirectories(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /**
     * Deletes files below {@code root} that are not listed in {@code keep} (relative,
     * {@code '/'}-separated) and then removes directories that became empty.
     */
    public static void deleteStale(Path root, Set<String> keep) throws IOException {
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream.sorted((a, b) -> b.compareTo(a)).collect(Collectors.toList());
        }
        for (Path path : paths) {
            if (path.equals(root)) {
                continue;
            }
            String relative = root.relativize(path).toString().replace('\\', '/');
            if (Files.isDirectory(path)) {
                try {
                    Files.deleteIfExists(path); // only succeeds when empty
                } catch (IOException ignored) {
                    // directory still contains kept files
                }
            } else if (!keep.contains(relative)) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Normalizes a suffix keyword: lowercased, leading dots removed. */
    public static String normalizeSuffix(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).trim();
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * Decides whether a file should be preprocessed or copied verbatim: the path must
     * match at least one include pattern (or there must be none) and no exclude pattern.
     */
    public static boolean matchesFilters(List<String> includes, List<String> excludes, String relativePath) {
        boolean included = includes.isEmpty();
        for (String include : includes) {
            if (matches(include, relativePath)) {
                included = true;
                break;
            }
        }
        if (!included) {
            return false;
        }
        for (String exclude : excludes) {
            if (matches(exclude, relativePath)) {
                return false;
            }
        }
        return true;
    }

    /** Ant style path matching ({@code **}, {@code *}, {@code ?}) on {@code '/'}-separated paths. */
    public static boolean matches(String pattern, String path) {
        return patternOf(pattern).matcher(path).matches();
    }

    private static Pattern patternOf(String pattern) {
        Pattern cached = PATTERN_CACHE.get(pattern);
        if (cached != null) {
            return cached;
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '/') {
                        regex.append("(?:.*/)?");
                        i++;
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        Pattern compiled = Pattern.compile(regex.toString());
        PATTERN_CACHE.put(pattern, compiled);
        return compiled;
    }
}
