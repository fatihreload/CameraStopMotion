package com.fatih.stopmotion.layers

import com.fatih.stopmotion.data.LayerEntity
import com.fatih.stopmotion.fx.ChromaState
import kotlin.math.abs

fun LayerEntity.timing(): ClipTiming = ClipTiming(
    startTick = startTick,
    lengthTicks = lengthTicks,
    isVideo = kind == LayerKind.VIDEO,
    mediaDurationMs = durationMs,
    trimStartMs = trimStartMs,
    loop = loop
)

/** Pengaturan chroma key layer ini (latar pengganti tidak dipakai: layer selalu transparan di bagian yang dihapus). */
fun LayerEntity.chromaState(): ChromaState =
    ChromaState(mode = chromaMode, tolerance = chromaTolerance, softness = chromaSoftness)

val LayerEntity.isVideo: Boolean get() = kind == LayerKind.VIDEO

/** Salinan dengan semua nilai dipaksa masuk rentang yang valid. Dipakai UI dan repository agar hasilnya sama. */
fun LayerEntity.sanitized(): LayerEntity = copy(
    name = name.trim().take(40).ifEmpty { "Layer" },
    kind = kind.coerceIn(LayerKind.IMAGE, LayerKind.VIDEO),
    durationMs = durationMs.coerceAtLeast(0L),
    aspect = if (aspect.isNaN() || aspect <= 0.01f) 1f else aspect.coerceAtMost(100f),
    startTick = startTick.coerceIn(0, LayerLimits.MAX_TICKS),
    lengthTicks = lengthTicks.coerceIn(1, LayerLimits.MAX_TICKS),
    trimStartMs = if (kind == LayerKind.VIDEO) trimStartMs.coerceIn(0L, (durationMs - 1L).coerceAtLeast(0L)) else 0L,
    centerX = LayerLimits.position(centerX),
    centerY = LayerLimits.position(centerY),
    scale = LayerLimits.scale(scale),
    rotation = LayerLimits.rotation(rotation),
    opacity = LayerLimits.opacity(opacity),
    chromaMode = chromaMode.coerceIn(0, 2),
    chromaTolerance = if (chromaTolerance.isNaN()) 0.4f else chromaTolerance.coerceIn(0f, 1f),
    chromaSoftness = if (chromaSoftness.isNaN()) 0.3f else chromaSoftness.coerceIn(0f, 1f),
    keyframes = Keyframes.encode(Keyframes.decode(keyframes))
)

// ---------------- Keyframe ----------------

/** Keyframe layer ini (terurut). Kosong bila layer tidak dianimasikan. */
val LayerEntity.keys: List<Keyframe> get() = Keyframes.decode(keyframes)

val LayerEntity.isAnimated: Boolean get() = !keyframes.isNullOrEmpty()

/** Pose statis layer (dipakai bila tidak ada keyframe). */
fun LayerEntity.basePose(): Pose = Pose(centerX, centerY, scale, rotation, opacity)

fun LayerEntity.withPose(p: Pose): LayerEntity =
    copy(centerX = p.x, centerY = p.y, scale = p.scale, rotation = p.rotation, opacity = p.opacity)

/** Pose layer pada tick film [tick] (keyframe dihitung dari awal layer). */
fun LayerEntity.poseAt(tick: Int): Pose =
    if (!isAnimated) basePose() else Keyframes.sample(keys, tick - startTick, basePose())

/** Salinan layer yang nilai posisi/ukuran/putar/transparannya sudah diisi pose pada [tick]. */
fun LayerEntity.posedAt(tick: Int): LayerEntity = if (!isAnimated) this else withPose(poseAt(tick))

/**
 * Menerapkan hasil edit UI ([edited], berasal dari salinan [posedAt]) pada layer ini.
 * - Perubahan keyframe langsung diterima.
 * - Layer tanpa animasi: [edited] diterima apa adanya.
 * - Layer beranimasi: mengubah posisi/ukuran/putar/transparan di [tick] otomatis membuat atau memperbarui
 *   keyframe di tick itu (auto key). Perubahan lain (waktu, chroma, dll.) tidak menyentuh animasi.
 */
fun LayerEntity.withEdit(edited: LayerEntity, tick: Int): LayerEntity {
    if (edited.keyframes != keyframes) return edited
    val ks = keys
    if (ks.isEmpty()) return edited
    val cur = poseAt(tick)
    var ep = edited.basePose()
    // sanitized() menormalkan putaran ke -180..180; jangan anggap itu perubahan pengguna.
    if (abs(LayerLimits.rotation(cur.rotation) - ep.rotation) < 1e-3f) ep = ep.copy(rotation = cur.rotation)
    val untouched = edited.withPose(basePose())
    if (ep.sameAs(cur)) return untouched
    val local = tick - startTick
    if (local < 0 || local >= lengthTicks) return untouched
    val existing = Keyframes.at(ks, local)
    if (existing == null && ks.size >= Keyframes.MAX_KEYS) return untouched
    val ease = existing?.ease ?: Keyframes.previous(ks, local)?.ease ?: Ease.LINEAR
    return untouched.copy(keyframes = Keyframes.encode(Keyframes.upsert(ks, Keyframe(local, ep, ease))))
}
