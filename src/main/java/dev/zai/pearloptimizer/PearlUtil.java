package dev.zai.pearloptimizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.HexFormat;

/**
 * Small byte-level helpers shared by the cache modules. Package-private
 * internal plumbing, not part of any module contract. These are
 * the single copies of the helpers that were previously duplicated as private
 * methods in {@link ShaderCacheManager}, {@link PersistentPipelineCache} and
 * {@link VulkanObjectCache}; every method is behavior-identical to the copies
 * it replaced.
 *
 * <p>This class also owns the absorption of stale atomic-write
 * leftovers ({@code *.tmp<threadId>} from a crashed previous session): the
 * cache housekeeping walks ({@link ShaderCacheManager#purgeNow} and
 * {@link PersistentPipelineCache#evictIfNeeded}) collect candidates and hand
 * them to {@link #absorbStaleLeftovers(List)}.</p>
 *
 * <p>It also owns the shared walk body of
 * both housekeeping walks ({@link #housekeepingWalk}: T-C1 orphan-meta
 * absorption and the T-C2 delete guard), plus the SPIR-V magic word the cache
 * readers and the shaderc probe validate against - one copy, one test contour
 * for both walks.</p>
 */
final class PearlUtil {

    /**
     * Captured once when this class loads - before any of the cache writers
     * can produce a temp file of this session (the writers all run through
     * code that touches this class first). Any {@code *.tmp<tid>} whose mtime
     * predates this instant therefore belongs to a previous process.
     *
     * <p>Clock caveat: a backwards NTP step mid-session could put a live temp
     * file's mtime before this instant. The consequence is bounded: the one
     * affected store's atomic move fails, the store swallows it (best-effort)
     * and the entry is a plain cache miss next launch.</p>
     */
    private static final long SESSION_START_MILLIS = System.currentTimeMillis();

    /**
     * First word of every valid SPIR-V module, read little-endian. This is the
     * single shared copy (previously duplicated as private
     * constants in {@link ShaderCacheManager} and {@link ShaderOptimization}.
     */
    static final int SPIRV_MAGIC = 0x07230203;

    /** Sidecar meta suffix shared by both cache layouts (shader cache and pipeline cache). */
    private static final String META_SUFFIX = ".meta.json";

    /**
     * True when the first 32-bit word of a little-endian SPIR-V stream is the
     * magic number. The three call sites read the word differently (buffer
     * view, int-buffer word view, raw instruction words) - only the comparison
     * is shared.
     */
    static boolean isSpirvMagic(int firstWord) {
        return firstWord == SPIRV_MAGIC;
    }

    /**
     * Purge grace window shared by both housekeeping walks
     * (shader-cache purge and pipeline-cache eviction). Entries younger than
     * this are never deleted: a store of THIS or a concurrently running game
     * instance (two clients sharing one instance directory) always surfaces
     * as a fresh mtime, and a walk that started microseconds later would
     * otherwise evict a just-written entry it saw as "oldest". The window
     * bounds the cap overshoot: at most ~one window of fresh writes stays
     * undeleted, and the next walk after the window reclaims them. 60 s is
     * ~120x the slowest observed store-to-hit latency and invisible at real
     * cache scales (caps are measured in megabytes, entries in kilobytes).
     */
    static final long FRESH_WRITE_GRACE_MS = 60_000L;

    /** Report counter: stale temp leftovers absorbed so far (never printed to the log). */
    private static final AtomicLong TMP_ABSORBED = new AtomicLong();

    private PearlUtil() {
    }

    /**
     * Fresh SHA-256 digest per call. Deliberately NOT ThreadLocal-cached:
     * {@code ShaderCacheManager.computeKey} feeds the returned digest across
     * several {@code feed()} calls, and in the middle of that sequence it
     * calls {@code includeFingerprint()}, which on a cold fingerprint calls
     * {@code sha256(String)} - a ThreadLocal-cached instance would be RESET
     * mid-feed, silently wiping every key component fed so far (verified by
     * ReentrancyProbe: the first key after a recordInclude() lost the
     * version/game/type/name/profile components). The per-call
     * {@code getInstance} cost is microseconds on cold compile/store paths
     * only; on a warm cache none of these run.
     */
    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static byte[] sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] sha256(byte[] b) {
        return sha256().digest(b);
    }

    private static final HexFormat HEX = HexFormat.of();

    static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    /**
     * Write-then-atomic-move; the temp file name carries the thread id, so
     * parallel stores never collide on the same target. Falls back to a plain
     * replace when the filesystem does not support atomic moves. A process
     * death between write and move leaves the temp file behind; those
     * leftovers carry no payload any reader would ever open and are absorbed
     * by the next housekeeping walk ({@link #absorbStaleLeftovers}).
     */
    static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp" + Thread.currentThread().threadId());
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Last modified time, or {@link Long#MAX_VALUE} when it cannot be read (never delete what we cannot stat). */
    static long safeMtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * True for our atomic-write leftovers: {@link #writeAtomically} names the
     * temp file {@code "<target>.tmp<threadId>"}, so a leftover has the suffix
     * {@code .tmp} followed by at least one digit. Anything else (real cache
     * payloads, foreign files, a {@code .tmp} without the numeric thread id)
     * is never matched.
     */
    static boolean isTmpLeftoverName(String name) {
        int i = name.lastIndexOf(".tmp");
        if (i < 0 || i + 4 == name.length()) {
            return false;
        }
        for (int j = i + 4; j < name.length(); j++) {
            char c = name.charAt(j);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Absorbs (deletes) stale leftovers of a crashed previous session: any
     * candidate whose mtime predates {@link #SESSION_START_MILLIS} cannot
     * belong to any write of the live process, and no cache reader ever opens
     * such a leftover file (loads go through the exact {@code <key>.spv}/
     * {@code <key>.bin} + meta names), so deleting it is pure garbage
     * collection. Live writes of the current session always have a fresh mtime
     * and are never touched. Deletion failures are swallowed - best-effort
     * housekeeping, the file is re-absorbed by the next walk.
     *
     * <p>Also used for orphan metas ({@code .meta.json}
     * whose payload file is already gone - the crash window of an older purge
     * that died between its two deletes). These are equally invisible to both
     * size caps (the caps count only {@code .spv} and {@code pc-*.bin}).</p>
     *
     * @return how many candidates were actually absorbed
     */
    static int absorbStaleLeftovers(List<Path> candidates) {
        int absorbed = 0;
        for (Path p : candidates) {
            try {
                if (safeMtime(p) < SESSION_START_MILLIS && Files.deleteIfExists(p)) {
                    absorbed++;
                }
            } catch (Throwable ignored) {
                // Best-effort; never surfaces, retried by the next walk.
            }
        }
        if (absorbed > 0) {
            TMP_ABSORBED.addAndGet(absorbed);
        }
        return absorbed;
    }

    /** Report counter: stale temp leftovers absorbed this session (never printed to the log). */
    static long tmpAbsorbed() {
        return TMP_ABSORBED.get();
    }

    // ------------------------------------------------------------------
    // Shared housekeeping walk body
    // ------------------------------------------------------------------

    /**
     * One payload candidate snapshot: the file path plus its mtime at walk
     * time (the delete guard compares against it). The
     * single copy previously duplicated as {@code SpvEntry} in
     * {@link ShaderCacheManager} and {@code PcBin} in
     * {@link PersistentPipelineCache}.
     */
    record CacheFile(Path path, long mtime) {
    }

    /**
     * Counters of one housekeeping walk, for the caller's verbose report.
     * {@code capExceeded} distinguishes the under-cap early return (no report,
     * even when stale orphans were absorbed) from an over-cap walk that could
     * not delete anything (report, because the cache is still over budget).
     */
    record WalkStats(boolean capExceeded, long totalBytes, int deleted, long deletedBytes,
                     int freshSkipped, int orphansAbsorbed) {
    }

    /**
     * Shared body of the two cache housekeeping walks (the shader-cache purge
     * of {@link ShaderCacheManager} and the pipeline-cache eviction of
     * {@link PersistentPipelineCache}). Behavior-identical to the two bodies
     * it replaces:
     *
     * <ol>
     *   <li>classify the tree into payload files, sidecar metas and tmp
     *       leftovers, snapshotting every payload's mtime at walk time;</li>
     *   <li>absorb stale tmp leftovers of a crashed previous session
     *       ({@link #absorbStaleLeftovers(List)});</li>
     *   <li>absorb stale orphan metas: a meta whose payload file is
     *       already gone can never be read again (meta-first load) and is
     *       invisible to the cap - a live store always has a fresh mtime and
     *       survives;</li>
     *   <li>when the payload bytes exceed {@code capBytes}: delete oldest-first
     *     under the T-C2 delete guard (re-stat before deleting; entries whose
     *     mtime changed since the snapshot or that are younger than
     *     {@link #FRESH_WRITE_GRACE_MS} belong to a live store and are
     *     skipped), removing the sidecar meta together with each payload.</li>
     * </ol>
     *
     * <p>Every failure is swallowed (best-effort housekeeping) and reported as
     * {@code null}; the caller prints its report line only for a completed
     * over-cap walk.</p>
     *
     * @param root          cache root to walk (null/absent = no-op)
     * @param recursive     walk the bucket tree ({@code Files.walk}) or the flat directory ({@code Files.list})
     * @param payloadPrefix required file-name prefix of a payload ({@code ""} = any; pipeline cache uses {@code "pc-"})
     * @param payloadSuffix required file-name suffix of a payload ({@code ".spv"} / {@code ".bin"})
     * @param deleteLock    optional lock serializing the delete phase (the shader-cache purge keeps its manager-class lock for the {@code purgeThrottle=false} multi-compiler mode); {@code null} = none
     * @param capBytes      payload byte cap
     * @return the walk counters, or {@code null} when the walk aborted early
     */
    static WalkStats housekeepingWalk(Path root, boolean recursive, String payloadPrefix,
                                      String payloadSuffix, Object deleteLock, long capBytes) {
        try {
            if (root == null || !Files.isDirectory(root)) {
                return null;
            }
            List<CacheFile> payloads = new ArrayList<>();
            List<Path> staleTmp = new ArrayList<>();
            List<Path> orphanMeta = new ArrayList<>();
            int prefixLen = payloadPrefix.length();
            int payloadSuffixLen = payloadSuffix.length();
            try (Stream<Path> stream = recursive ? Files.walk(root) : Files.list(root)) {
                stream.forEach(p -> classify(p, payloadPrefix, prefixLen, payloadSuffix, payloadSuffixLen,
                    payloads, staleTmp, orphanMeta));
            }
            if (!staleTmp.isEmpty()) {
                absorbStaleLeftovers(staleTmp);
            }
            int orphansAbsorbed = 0;
            if (!orphanMeta.isEmpty()) {
                orphansAbsorbed = absorbStaleLeftovers(orphanMeta);
            }
            long total = 0;
            for (CacheFile e : payloads) {
                try {
                    total += Files.size(e.path());
                } catch (IOException ignored) {
                }
            }
            if (total <= capBytes) {
                // Under-cap: the pre-5.2 bodies returned here without a report,
                // even when orphans were just absorbed - keep that contract.
                return new WalkStats(false, total, 0, 0, 0, orphansAbsorbed);
            }
            payloads.sort(Comparator.comparingLong(CacheFile::mtime));
            long excess = total - capBytes;
            long now = System.currentTimeMillis();
            DeleteOutcome outcome = deleteOldest(payloads, excess, now, payloadSuffixLen, deleteLock);
            return new WalkStats(true, total, outcome.deleted(), outcome.deletedBytes(),
                outcome.freshSkipped(), orphansAbsorbed);
        } catch (Throwable ignored) {
            // Housekeeping must never surface; the next walk re-collects everything.
            return null;
        }
    }

    /**
     * Classifies one walked file into a payload, an orphan meta, or a stale
     * tmp leftover, snapshotting the payload's mtime at walk time. Extracted
     * from the walk lambda so the main flow reads linearly and the
     * classification rules have a single test surface.
     */
    private static void classify(Path p, String payloadPrefix, int prefixLen,
                                 String payloadSuffix, int payloadSuffixLen,
                                 List<CacheFile> payloads, List<Path> staleTmp, List<Path> orphanMeta) {
        String name = p.getFileName().toString();
        boolean prefixOk = prefixLen == 0 || name.startsWith(payloadPrefix);
        if (prefixOk && name.endsWith(payloadSuffix)) {
            payloads.add(new CacheFile(p, safeMtime(p)));
        } else if (prefixOk && name.endsWith(META_SUFFIX)) {
            // T-C1: a meta whose payload is already gone can never be read
            // again (meta-first load); collected for the stale-rule absorption.
            if (!Files.exists(p.resolveSibling(
                    name.substring(0, name.length() - META_SUFFIX.length()) + payloadSuffix))) {
                orphanMeta.add(p);
            }
        } else if (isTmpLeftoverName(name)) {
            staleTmp.add(p);
        }
    }

    /** Delete-phase counters of one housekeeping walk. */
    private record DeleteOutcome(int deleted, long deletedBytes, int freshSkipped) {
    }

    /** Runs the delete phase under {@code deleteLock} when one is supplied. */
    private static DeleteOutcome deleteOldest(List<CacheFile> payloads, long excess, long now,
                                              int payloadSuffixLen, Object deleteLock) {
        if (deleteLock == null) {
            return deleteOldestUnlocked(payloads, excess, now, payloadSuffixLen);
        }
        synchronized (deleteLock) {
            return deleteOldestUnlocked(payloads, excess, now, payloadSuffixLen);
        }
    }

    /** Oldest-first deletion with the T-C2 re-stat + grace-window guard; best-effort, IO failures are skipped. */
    private static DeleteOutcome deleteOldestUnlocked(List<CacheFile> payloads, long excess, long now,
                                                      int payloadSuffixLen) {
        int deleted = 0;
        long deletedBytes = 0;
        int freshSkipped = 0;
        for (CacheFile e : payloads) {
            if (excess <= 0) {
                break;
            }
            Path p = e.path();
            try {
                // T-C2 delete guard: re-stat before deleting. A file replaced
                // after the walk has a different mtime; a just-written file is
                // younger than the grace window. Both mean the entry belongs to
                // a live store - skip it and let the next walk decide.
                long mtime = safeMtime(p);
                if (mtime != e.mtime() || now - mtime < FRESH_WRITE_GRACE_MS) {
                    freshSkipped++;
                    continue;
                }
                long sz = Files.size(p);
                if (Files.deleteIfExists(p)) {
                    deleted++;
                    deletedBytes += sz;
                    excess -= sz;
                    String name = p.getFileName().toString();
                    Files.deleteIfExists(p.resolveSibling(
                        name.substring(0, name.length() - payloadSuffixLen) + META_SUFFIX));
                }
            } catch (IOException ignored) {
            }
        }
        return new DeleteOutcome(deleted, deletedBytes, freshSkipped);
    }
}
