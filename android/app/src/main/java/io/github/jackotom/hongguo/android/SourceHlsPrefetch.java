package io.github.jackotom.hongguo.android;

import android.net.Uri;

import androidx.media3.common.C;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Background whole-episode prefetch for an HLS source. It reuses the same session headers,
 * cookie and dynamic key authorization as playback, and writes each manifest / key / segment
 * into SourceHlsCache so the episode becomes available offline.
 */
final class SourceHlsPrefetch {
    interface Progress {
        void onProgress(int done, int total);
        void onDone(boolean ok, String message);
    }

    private static final int MAX_PLAYLIST = 2 * 1024 * 1024;
    private static final int MAX_SEGMENT = 64 * 1024 * 1024;
    private static final int MAX_TOTAL = 400 * 1024 * 1024;

    private final SourcePlaybackSessions.Session session;
    private final OkHttpClient client;
    private final SourceHlsCache cache;
    private final Progress progress;
    private final AtomicBoolean canceled = new AtomicBoolean(false);
    private volatile Call current;

    SourceHlsPrefetch(SourcePlaybackSessions.Session session, OkHttpClient client,
                      SourceHlsCache cache, Progress progress) {
        this.session = session;
        this.client = client;
        this.cache = cache;
        this.progress = progress;
    }

    void cancel() { canceled.set(true); Call call = current; if (call != null) call.cancel(); }

    void start() {
        new Thread(() -> {
            try {
                byte[] manifest = fetch(session.url, C.DATA_TYPE_MANIFEST, MAX_PLAYLIST);
                cache.write(session.sourceId, C.DATA_TYPE_MANIFEST, session.url, manifest);
                List<URI> playlists = new ArrayList<>();
                List<URI> segments = new ArrayList<>();
                parsePlaylist(session.url, new String(manifest, StandardCharsets.UTF_8), playlists, segments);
                // Follow master playlists (up to a few levels) collecting real media segments.
                int depth = 0;
                while (!playlists.isEmpty() && depth++ < 4 && !canceled.get()) {
                    URI next = playlists.remove(0);
                    byte[] body = fetch(next, C.DATA_TYPE_MANIFEST, MAX_PLAYLIST);
                    cache.write(session.sourceId, C.DATA_TYPE_MANIFEST, next, body);
                    parsePlaylist(next, new String(body, StandardCharsets.UTF_8), playlists, segments);
                }
                if (canceled.get()) { progress.onDone(false, "缓存已取消"); return; }
                if (segments.isEmpty()) { progress.onDone(false, "来源未返回可缓存的分片"); return; }
                int total = segments.size(), done = 0, bytesTotal = 0;
                for (URI segment : segments) {
                    if (canceled.get() || !session.active()) { progress.onDone(false, "缓存已取消"); return; }
                    byte[] piece = fetch(segment, C.DATA_TYPE_MEDIA, MAX_SEGMENT);
                    bytesTotal += piece.length;
                    if (bytesTotal > MAX_TOTAL) { progress.onDone(false, "本集过大，已停止缓存"); return; }
                    cache.write(session.sourceId, C.DATA_TYPE_MEDIA, segment, piece);
                    done++;
                    progress.onProgress(done, total);
                }
                progress.onDone(true, "已缓存 " + done + " 个分片，可离线播放");
            } catch (InterruptedException e) {
                progress.onDone(false, "缓存已取消");
            } catch (Exception e) {
                progress.onDone(false, safeMessage(e));
            }
        }, "source-hls-prefetch").start();
    }

    /** Parse a playlist into child media playlists and segment URIs. */
    private void parsePlaylist(URI base, String body, List<URI> playlists, List<URI> segments) throws IOException {
        SourcePlaybackSessions.checkPlaylist(body.getBytes(StandardCharsets.UTF_8));
        boolean master = false;
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) master = true;
            if (line.isEmpty() || line.startsWith("#")) continue;
            URI resolved = SourcePlaybackSessions.redirect(base, line);
            if (master) playlists.add(resolved); else segments.add(resolved);
        }
    }

    private byte[] fetch(URI target, int type, int maxBytes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        int redirects = 0;
        while (true) {
            if (!session.active()) throw new InterruptedException();
            Request.Builder request = new Request.Builder().url(target.toASCIIString())
                .header("User-Agent", session.userAgent).header("Accept-Encoding", "identity");
            if (!session.referer.isEmpty()) request.header("Referer", session.referer);
            String cookie = session.cookieFor(target);
            if (!cookie.isEmpty()) request.header("Cookie", cookie);
            if (type == C.DATA_TYPE_DRM) {
                String token = session.authorizeKey(target, deadline);
                if (!token.isEmpty()) request.header("X-Preview-Token", token);
            }
            Call call = client.newCall(request.build());
            current = call;
            long timeout = deadline - System.nanoTime();
            if (timeout <= 0) throw new IOException("缓存请求超时");
            call.timeout().timeout(timeout, TimeUnit.NANOSECONDS);
            try (Response response = call.execute()) {
                int code = response.code();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (++redirects > 3) throw new IOException("来源跳转次数超限");
                    target = SourcePlaybackSessions.redirect(target, response.header("Location"));
                    continue;
                }
                if (code != 200 && code != 206) throw new IOException("来源拒绝缓存请求（HTTP " + code + "）");
                ResponseBody body = response.body();
                if (body == null) throw new IOException("来源未返回数据");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = body.byteStream()) {
                    byte[] buf = new byte[8192];
                    int count;
                    while ((count = in.read(buf)) != -1) {
                        if (out.size() + count > maxBytes) throw new IOException("来源媒体超过大小限制");
                        out.write(buf, 0, count);
                    }
                }
                return out.toByteArray();
            }
        }
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.length() > 120 ? "缓存未完成，请检查网络或来源可用性" : m;
    }
}
