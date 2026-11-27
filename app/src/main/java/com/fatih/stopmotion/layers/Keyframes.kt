package com.fatih.stopmotion.layers

import kotlin.math.abs

/** Posisi, ukuran, putaran, dan transparansi layer pada satu saat. Posisi: 0..1 = di dalam layar film. */
data class Pose(val x: Float, val y: Float, val scale: Float, val rotation: Float, val opacity: Float) {
    fun sameAs(o: Pose, eps: Float = 1e-4f): Boolean =
        abs(x - o.x) < eps && abs(y - o.y) < eps && abs(scale - o.scale) < eps &&
            abs(rotation - o.rotation) < 1e-3f && abs(opacity - o.opacity) < eps
}

/** Cara bergerak dari sebuah keyframe menuju keyframe berikutnya. */
object Ease {
    const val LINEAR = 0
    /** Mulai dan berhenti pelan (smoothstep). */
    const val SMOOTH = 1
    /** Diam di nilai keyframe ini, lalu melompat tepat di keyframe berikutnya. */
    const val HOLD = 2

    val labels = listOf("Lurus", "Halus", "Tahan")

    fun apply(ease: Int, t: Float): Float = when (ease) {
        HOLD -> 0f
        SMOOTH -> t * t * (3f - 2f * t)
        else -> t
    }
}

/**
 * Satu keyframe. [tick] dihitung dari awal layer (0 = tick pertama layer tampil), jadi menggeser
 * "Mulai" layer ikut menggeser animasinya.
 */
data class Keyframe(val tick: Int, val pose: Pose, val ease: Int = Ease.LINEAR)

/**
 * Animasi keyframe layer (tanpa Android/Room, mudah diuji).
 * Aturan: sebelum keyframe pertama dan setelah yang terakhir, layer memakai nilai keyframe terdekat.
 * Di antara dua keyframe, nilai bergerak mengikuti [Keyframe.ease] milik keyframe yang di depan.
 */
object Keyframes {
    const val MAX_KEYS = 100
    private const val MAX_ENCODED = 20_000

    fun cleanPose(p: Pose): Pose = Pose(
        LayerLimits.position(p.x), LayerLimits.position(p.y), LayerLimits.scale(p.scale),
        LayerLimits.keyRotation(p.rotation), LayerLimits.opacity(p.opacity)
    )

    fun clean(k: Keyframe): Keyframe =
        Keyframe(k.tick.coerceIn(0, LayerLimits.MAX_TICKS), cleanPose(k.pose), k.ease.coerceIn(Ease.LINEAR, Ease.HOLD))

    /** Rapikan nilai, urutkan menurut tick, buang tick kembar (yang pertama dipakai), batasi jumlah. */
    fun normalize(keys: List<Keyframe>): List<Keyframe> =
        keys.map(::clean).sortedBy { it.tick }.distinctBy { it.tick }.take(MAX_KEYS)

    /** Tambah keyframe, atau ganti bila sudah ada keyframe di tick yang sama. */
    fun upsert(keys: List<Keyframe>, key: Keyframe): List<Keyframe> =
        normalize(keys.filter { it.tick != key.tick } + key)

    fun remove(keys: List<Keyframe>, tick: Int): List<Keyframe> = keys.filter { it.tick != tick }

    fun at(keys: List<Keyframe>, tick: Int): Keyframe? = keys.firstOrNull { it.tick == tick }

    fun previous(keys: List<Keyframe>, tick: Int): Keyframe? = keys.lastOrNull { it.tick < tick }

    fun next(keys: List<Keyframe>, tick: Int): Keyframe? = keys.firstOrNull { it.tick > tick }

    /** Pose pada [tick] (relatif awal layer). Tanpa keyframe: [base]. [keys] harus sudah terurut. */
    fun sample(keys: List<Keyframe>, tick: Int, base: Pose): Pose {
        if (keys.isEmpty()) return base
        val first = keys.first()
        if (tick <= first.tick) return first.pose
        val last = keys.last()
        if (tick >= last.tick) return last.pose
        var i = 0
        while (i + 1 < keys.size && keys[i + 1].tick <= tick) i++
        val a = keys[i]
        val b = keys[i + 1]
        val t = Ease.apply(a.ease, (tick - a.tick).toFloat() / (b.tick - a.tick).toFloat())
        fun mix(p: Float, q: Float) = p + (q - p) * t
        return Pose(
            mix(a.pose.x, b.pose.x), mix(a.pose.y, b.pose.y), mix(a.pose.scale, b.pose.scale),
            mix(a.pose.rotation, b.pose.rotation), mix(a.pose.opacity, b.pose.opacity)
        )
    }

    /** Format simpan: "tick:x:y:ukuran:putar:transparan:ease;..." atau null bila tidak ada keyframe. */
    fun encode(keys: List<Keyframe>): String? {
        val list = normalize(keys)
        if (list.isEmpty()) return null
        return list.joinToString(";") { k ->
            "${k.tick}:${k.pose.x}:${k.pose.y}:${k.pose.scale}:${k.pose.rotation}:${k.pose.opacity}:${k.ease}"
        }
    }

    /** Kebalikan [encode]. Data rusak atau tidak masuk akal dibuang, tidak pernah melempar error. */
    fun decode(text: String?): List<Keyframe> {
        if (text.isNullOrEmpty() || text.length > MAX_ENCODED) return emptyList()
        val out = ArrayList<Keyframe>()
        for (part in text.split(';')) {
            val f = part.split(':')
            if (f.size != 7) continue
            val tick = f[0].toIntOrNull() ?: continue
            val nums = FloatArray(5)
            var ok = true
            for (i in 0 until 5) {
                val v = f[i + 1].toFloatOrNull()
                if (v == null || v.isNaN()) { ok = false; break }
                nums[i] = v
            }
            if (!ok) continue
            val ease = f[6].toIntOrNull() ?: Ease.LINEAR
            out.add(Keyframe(tick, Pose(nums[0], nums[1], nums[2], nums[3], nums[4]), ease))
        }
        return normalize(out)
    }
}
