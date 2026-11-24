package io.github.jackotom.hongguo.android;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * On-device transparent cache for third-party source media (HLS manifests, keys and segments).
 * It lives below SourceDataSource so the source-specific headers / dynamic key authorization keep
 * working: the first play writes every response to disk, later plays read it back offline.
 */
final class SourceHlsCache {
    private static final long BUDGET = 512L * 1024 * 1024;      // 512 MiB across all sources
    private static final long MAX_ENTRY = 64L * 1024 * 1024;    // single segment cap matches player
    private static final long TTL = 14L * 24 * 60 * 60 * 1000;  // 14 days
    private static volatile SourceHlsCache instance;

    private final File root;
    private final AtomicLong bytes = new AtomicLong(-1);

    private SourceHlsCache(Context context) {
        this.root = new File(context.getFilesDir(), "source-hls-cache");
    }

    static synchronized SourceHlsCache get(Context context) {
        if (instance == null) instance = new SourceHlsCache(context.getApplicationContext());
        return instance;
    }

    /** Stable key: source + scheme/host/path + type. Signed query strings are intentionally
     *  omitted so a re-signed URL still hits the previously cached segment. */
    private String key(String sourceId, int type, URI url) {
        StringBuilder raw = new StringBuilder().append(sourceId).append('|').append(type).append('|')
            .append(url.getScheme()).append("://").append(url.getHost().toLowerCase(Locale.ROOT))
            .append(url.getPath());
        // Keep stable query params that identify content but drop volatile signatures/tokens.
        String query = url.getRawQuery();
        if (query != null) {
            String[] pairs = query.split("&");
            Arrays.sort(pairs);
            for (String pair : pairs) {
                String lower = pair.toLowerCase(Locale.ROOT);
                if (lower.startsWith("sign=") || lower.startsWith("token=") || lower.startsWith("x-") ||
                    lower.startsWith("expires") || lower.startsWith("timestamp=") || lower.startsWith("auth") ||
                    lower.startsWith("signature=") || lower.startsWith("key=")) continue;
                raw.append('?').append(pair);
            }
        }
        return sha(raw.toString());
    }

    private static String sha(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 16; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private File file(String key) { return new File(root, key + ".bin"); }

    /** Returns a stream over cached bytes, or null on miss. Caller closes it. */
    InputStream read(String sourceId, int type, URI url) {
        File target = file(key(sourceId, type, url));
        if (!target.isFile() || System.currentTimeMillis() - target.lastModified() > TTL) return null;
        try { return new FileInputStream(target); }
        catch (IOException ignored) { return null; }
    }

    boolean has(String sourceId, int type, URI url) {
        File target = file(key(sourceId, type, url));
        return target.isFile() && System.currentTimeMillis() - target.lastModified() <= TTL;
    }

    /** Atomically publish bytes captured from the network. Accepts at most MAX_ENTRY. */
    void write(String sourceId, int type, URI url, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_ENTRY) return;
        File target = file(key(sourceId, type, url));
        File temp = new File(root, target.getName() + ".tmp");
        try {
            if (!root.isDirectory() && !root.mkdirs()) return;
            try (OutputStream out = new FileOutputStream(temp)) { out.write(bytes); }
            long used = totalBytes();
            if (used + bytes.length > BUDGET) prune(used + bytes.length - BUDGET);
            if (temp.exists() && !temp.renameTo(target)) {
                // rename can fail across filesystems; fall back to direct replace.
                if (target.exists() && !target.delete()) return;
                if (!temp.renameTo(target)) return;
            }
            this.bytes.set(-1);
        } catch (IOException ignored) {
            temp.delete();
        }
    }

    /** Best-effort LRU by last-modified until we are under budget. */
    private void prune(long need) {
        File[] entries = root.listFiles();
        if (entries == null) return;
        Arrays.sort(entries, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long freed = 0;
        for (File entry : entries) {
            if (freed >= need) break;
            long size = entry.length();
            if (entry.delete()) freed += size;
        }
    }

    private long totalBytes() {
        long cached = bytes.get();
        if (cached >= 0) return cached;
        long total = 0;
        File[] entries = root.listFiles();
        if (entries != null) for (File entry : entries) if (entry.isFile()) total += entry.length();
        bytes.set(total);
        return total;
    }

    /** Called when a cached entry is (re)written so the budget accounting stays fresh. */
    void invalidateAccounting() { bytes.set(-1); }

    /** Cache the resolved media descriptor (URL + headers) for an episode so playback can be
     *  re-created offline after the first successful resolve. */
    private String descriptorKey(String sourceId, String seriesKey, String episodeId) {
        return sha("descriptor|" + sourceId + "|" + seriesKey + "|" + episodeId);
    }
    void saveDescriptor(String sourceId, String seriesKey, String episodeId, String json) {
        File target = new File(root, descriptorKey(sourceId, seriesKey, episodeId) + ".json");
        try {
            if (!root.isDirectory() && !root.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) { target.delete(); }
    }
    String loadDescriptor(String sourceId, String seriesKey, String episodeId) {
        File target = new File(root, descriptorKey(sourceId, seriesKey, episodeId) + ".json");
        if (!target.isFile() || System.currentTimeMillis() - target.lastModified() > TTL) return null;
        try {
            byte[] data = new byte[(int) target.length()];
            try (FileInputStream in = new FileInputStream(target)) {
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            return new String(data, 0, (int) Math.min(target.length(), Integer.MAX_VALUE), StandardCharsets.UTF_8);
        } catch (IOException ignored) { return null; }
    }

    /** Expose the stable key for the prefetcher so it writes bytes under the same name the
     *  player reads. */
    String cacheKey(String sourceId, int type, URI url) { return key(sourceId, type, url); }

    /** Write pre-fetched bytes for a specific key (used by the HLS prefetcher). */
    void writeKey(String cacheKey, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_ENTRY) return;
        File target = file(cacheKey);
        File temp = new File(root, target.getName() + ".tmp");
        try {
            if (!root.isDirectory() && !root.mkdirs()) return;
            try (OutputStream out = new FileOutputStream(temp)) { out.write(bytes); }
            long used = totalBytes();
            if (used + bytes.length > BUDGET) prune(used + bytes.length - BUDGET);
            if (temp.exists() && !temp.renameTo(target)) {
                if (target.exists() && !target.delete()) return;
                if (!temp.renameTo(target)) return;
            }
            this.bytes.set(-1);
        } catch (IOException ignored) { temp.delete(); }
    }
}
