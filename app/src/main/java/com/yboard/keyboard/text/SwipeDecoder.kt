package com.yboard.keyboard.text

import kotlin.math.ceil
import kotlin.math.sqrt

/** A point in keyboard-view pixels. */
data class Pt(val x: Float, val y: Float)

/** A dictionary word with frequency 1..255 (higher = more common). */
data class WordFreq(val word: String, val freq: Int)

/**
 * Gesture ("swipe" / "glide") typing decoder, SHARK2-style.
 *
 * Pipeline for one gesture:
 *  1. Normalise the path to key-width units, resample it to [SAMPLES] equidistant points (for shape
 *     scoring) and to a dense filter path with ~[FILTER_STEP] key-width spacing (for pruning/location).
 *  2. Start / end keys = the few keys nearest to the first / last touch point.
 *  3. For every dictionary word that starts with a start key and ends with an end key, check that its
 *     letters (consecutive repeats collapsed; characters without a letter key, e.g. ' - ׳, skipped) are visited in
 *     order: each key must have a filter-path point within [PASS_RADIUS] key widths, at or after the
 *     previous letter's match. One interior key can use [CORNER_RADIUS] to allow rounded turns.
 *  4. Survivors are scored (lower is better), roughly a Gaussian negative log-likelihood:
 *        cost = weighted rigid/warped shape + ordered location + endpoint cost + frequency prior
 *     - shape: mean SQUARED distance between corresponding points of the user path and the word's
 *       ideal path (polyline through its key centres), both resampled to [SAMPLES] equidistant points.
 *     - location: SUM over the word's (collapsed) letters of the squared distance from each key centre
 *       to the path, using the best monotonic (in-order) alignment of letters to path points. Summing
 *       (not averaging) makes an extra letter that merely lies on the path nearly free but never a
 *       bonus, so "tie" beats "tire" and "for" beats "four" unless the longer word is more frequent.
 *     All distances are in key widths.
 *
 * Pure Kotlin, no Android dependencies. An instance keeps scratch buffers, so calls are serialised
 * (it is safe to call from several threads, but calls run one at a time).
 */
class SwipeDecoder(
    /** All candidate words whose first letter (lowercased) is [c], sorted by freq descending. */
    private val wordsStartingWith: (Char) -> List<WordFreq>,
) {
    data class Candidate(val word: String, val cost: Float)
    private var frequencyPrior = true

    // ------------------------------------------------------------------------------------------
    // Layout cache (rebuilt only when the key map changes).
    // ------------------------------------------------------------------------------------------
    private var layoutSrc: Map<Char, Pt>? = null
    private var keyCount = 0
    private var keyChars = CharArray(0) // sorted ascending, for binary search
    private var keyPxX = FloatArray(0)
    private var keyPxY = FloatArray(0)
    private var keyX = FloatArray(0) // in key-width units for the current call
    private var keyY = FloatArray(0)

    // ------------------------------------------------------------------------------------------
    // Scratch buffers, reused across calls.
    // ------------------------------------------------------------------------------------------
    private var rawX = FloatArray(128)
    private var rawY = FloatArray(128)
    private val ux = FloatArray(SAMPLES)
    private val uy = FloatArray(SAMPLES)
    private val tx = FloatArray(SAMPLES)
    private val ty = FloatArray(SAMPLES)
    private val fx = FloatArray(MAX_FILTER_POINTS)
    private val fy = FloatArray(MAX_FILTER_POINTS)
    private val dpRow = FloatArray(MAX_FILTER_POINTS)
    private val warpA = FloatArray(SAMPLES + 1)
    private val warpB = FloatArray(SAMPLES + 1)
    private var keyDist2 = FloatArray(0) // [key * filterCount + i] squared distance key centre -> filter point
    private var nearNext = IntArray(0) // [key * (filterCount + 1) + i] first j >= i with key near point j
    private var relaxedNearNext = IntArray(0)
    private var isStartKey = BooleanArray(0)
    private var isEndKey = BooleanArray(0)
    private val startKeys = IntArray(MAX_END_KEYS)
    private var seq = IntArray(32)
    private var polyX = FloatArray(32)
    private var polyY = FloatArray(32)
    private var filterCount = 0

    // Top-k results.
    private var bestCost = FloatArray(8)
    private var bestWord = arrayOfNulls<String>(8)
    private var bestCount = 0
    private var bestLimit = 0

    /**
     * @param path finger samples in order (may be 10–400 points, unevenly spaced).
     * @param keyCenters lowercase letter -> center of that key, for the CURRENT layout.
     * @param keyWidth typical letter-key width in px (use for all distance thresholds/normalization).
     * @return up to [limit] words, best first, in their dictionary spelling. Empty if nothing fits.
     */
    @Synchronized
    fun decode(path: List<Pt>, keyCenters: Map<Char, Pt>, keyWidth: Float, limit: Int = 4): List<String> {
        return decodeScored(path, keyCenters, keyWidth, limit, includeFrequency = true).map { it.word }
    }

    /** Geometry candidates for Qwen. Frequency is disabled in the production local-model path. */
    @Synchronized
    fun decodeScored(path: List<Pt>, keyCenters: Map<Char, Pt>, keyWidth: Float, limit: Int = 24, includeFrequency: Boolean = false): List<Candidate> {
        frequencyPrior = includeFrequency
        if (limit <= 0 || path.isEmpty() || keyCenters.isEmpty()) return emptyList()
        if (!(keyWidth > 0f) || keyWidth.isInfinite()) return emptyList()
        setLayout(keyCenters)
        val k = keyCount
        if (k == 0) return emptyList()
        val inv = 1f / keyWidth
        for (i in 0 until k) {
            keyX[i] = keyPxX[i] * inv
            keyY[i] = keyPxY[i] * inv
        }

        // 1. Copy the path in key-width units, dropping invalid and repeated samples.
        if (rawX.size < path.size) {
            rawX = FloatArray(path.size)
            rawY = FloatArray(path.size)
        }
        var n = 0
        for (p in path) {
            if (p.x.isNaN() || p.y.isNaN() || p.x.isInfinite() || p.y.isInfinite()) continue
            val x = p.x * inv
            val y = p.y * inv
            if (n > 0 && x == rawX[n - 1] && y == rawY[n - 1]) continue
            rawX[n] = x
            rawY[n] = y
            n++
        }
        if (n == 0) return emptyList()

        val length = polylineLength(rawX, rawY, n)
        resample(rawX, rawY, n, length, SAMPLES, ux, uy)
        val f = (ceil(length / FILTER_STEP).toInt() + 1).coerceIn(2, MAX_FILTER_POINTS)
        filterCount = f
        resample(rawX, rawY, n, length, f, fx, fy)

        // 2. Distances from every key to every filter point + "next near point" table.
        if (keyDist2.size < k * f) keyDist2 = FloatArray(k * f)
        if (nearNext.size < k * (f + 1)) nearNext = IntArray(k * (f + 1))
        if (relaxedNearNext.size < k * (f + 1)) relaxedNearNext = IntArray(k * (f + 1))
        val r2 = PASS_RADIUS * PASS_RADIUS
        val cornerRadius2 = CORNER_RADIUS * CORNER_RADIUS
        for (key in 0 until k) {
            val cx = keyX[key]
            val cy = keyY[key]
            val db = key * f
            val nb = key * (f + 1)
            var next = f
            nearNext[nb + f] = f
            var relaxedNext = f
            relaxedNearNext[nb + f] = f
            for (i in f - 1 downTo 0) {
                val dx = fx[i] - cx
                val dy = fy[i] - cy
                val d2 = dx * dx + dy * dy
                keyDist2[db + i] = d2
                if (d2 <= r2) next = i
                nearNext[nb + i] = next
                if (d2 <= cornerRadius2) relaxedNext = i
                relaxedNearNext[nb + i] = relaxedNext
            }
        }

        // 3. Start and end keys.
        isStartKey.fill(false)
        isEndKey.fill(false)
        val nStart = nearestKeys(rawX[0], rawY[0], startKeys)
        for (i in 0 until nStart) isStartKey[startKeys[i]] = true
        val endKeys = IntArray(MAX_END_KEYS)
        val nEnd = nearestKeys(rawX[n - 1], rawY[n - 1], endKeys)
        for (i in 0 until nEnd) isEndKey[endKeys[i]] = true

        // 4. Candidates.
        resetBest(limit)
        for (s in 0 until nStart) {
            val words = wordsStartingWith(keyChars[startKeys[s]])
            for (wf in words) consider(wf)
        }

        val out = ArrayList<Candidate>(bestCount)
        for (i in 0 until bestCount) out.add(Candidate(bestWord[i]!!, bestCost[i]))
        for (i in 0 until bestCount) bestWord[i] = null
        return out
    }

    private fun consider(wf: WordFreq) {
        val word = wf.word
        val len = word.length
        if (len < 2) return

        // Cheap end-key check first: last character that is on the keyboard.
        var lastKey = -1
        var i = len - 1
        while (i >= 0) {
            val kk = keyIndexOf(word[i].lowercaseChar())
            if (kk >= 0) {
                lastKey = kk
                break
            }
            i--
        }
        if (lastKey < 0 || !isEndKey[lastKey]) return

        // Key sequence with consecutive repeats collapsed.
        if (seq.size < len) seq = IntArray(len)
        var m = 0
        var letters = 0
        var prev = -1
        for (c in word) {
            val kk = keyIndexOf(c.lowercaseChar())
            if (kk < 0) continue
            letters++
            if (kk != prev) {
                seq[m++] = kk
                prev = kk
            }
        }
        if (letters < 2 || !isStartKey[seq[0]]) return

        if (!passesKeys(m)) return

        // Frequency prior, used to prune before the expensive channels.
        val freq = wf.freq.coerceIn(1, 255)
        // AOSP's byte is already a compressed frequency score. Taking its logarithm again
        // erased useful differences between common words and rare lookalikes.
        val prior = if (frequencyPrior) W_FREQ * (255f - freq) / 32f else 0f
        if (bestCount == bestLimit && prior >= bestCost[bestCount - 1]) return

        // Shape channel.
        if (polyX.size < m) {
            polyX = FloatArray(m)
            polyY = FloatArray(m)
        }
        for (j in 0 until m) {
            polyX[j] = keyX[seq[j]]
            polyY[j] = keyY[seq[j]]
        }
        val idealLength = polylineLength(polyX, polyY, m)
        resample(polyX, polyY, m, idealLength, SAMPLES, tx, ty)
        var s = 0f
        for (p in 0 until SAMPLES) {
            val dx = ux[p] - tx[p]
            val dy = uy[p] - ty[p]
            s += dx * dx + dy * dy
        }
        val shape = s / SAMPLES
        if (shape > MAX_SHAPE) return
        // A narrow dynamic alignment tolerates rounded corners and unevenly shortened segments.
        // Keep the rigid channel too: a different detour must not collapse to the same word.
        var cost = prior + W_SHAPE * (0.35f * shape + 0.65f * warpedShape())
        val startDx = ux[0] - tx[0]
        val startDy = uy[0] - ty[0]
        val endDx = ux[SAMPLES - 1] - tx[SAMPLES - 1]
        val endDy = uy[SAMPLES - 1] - ty[SAMPLES - 1]
        cost += 0.35f * (startDx * startDx + startDy * startDy + endDx * endDx + endDy * endDy)
        if (bestCount == bestLimit && cost >= bestCost[bestCount - 1]) return

        // Location channel.
        cost += W_LOC * orderedLocation(m, filterCount)
        offer(word, cost)
    }

    private fun passesKeys(length: Int): Boolean {
        val end = filterCount
        val stride = end + 1
        var strict = 0
        var withRoundedCorner = end
        for (i in 0 until length) {
            val offset = seq[i] * stride
            withRoundedCorner = nearNext[offset + withRoundedCorner]
            // Permit one clipped interior corner. Endpoints and other letters still use the normal radius.
            if (i > 0 && i < length - 1) {
                withRoundedCorner = minOf(withRoundedCorner, relaxedNearNext[offset + strict])
            }
            strict = nearNext[offset + strict]
            if (strict == end && withRoundedCorner == end) return false
        }
        return true
    }

    private fun warpedShape(): Float {
        var prev = warpA
        var row = warpB
        prev.fill(Float.POSITIVE_INFINITY)
        prev[0] = 0f
        for (i in 1..SAMPLES) {
            row.fill(Float.POSITIVE_INFINITY)
            for (j in maxOf(1, i - 12)..minOf(SAMPLES, i + 12)) {
                val dx = ux[i - 1] - tx[j - 1]
                val dy = uy[i - 1] - ty[j - 1]
                row[j] = dx * dx + dy * dy + minOf(prev[j - 1], prev[j], row[j - 1])
            }
            val swap = prev; prev = row; row = swap
        }
        return prev[SAMPLES] / SAMPLES
    }

    /**
     * Minimal sum of squared key-to-path distances over monotonic (in-order) alignments of the word's
     * keys to the filter path. O(m * f) dynamic programme with a running prefix minimum.
     */
    private fun orderedLocation(m: Int, f: Int): Float {
        val row = dpRow
        val b0 = seq[0] * f
        for (i in 0 until f) row[i] = maxOf(0f, keyDist2[b0 + i] - 0.16f)
        for (j in 1 until m) {
            val b = seq[j] * f
            var run = Float.MAX_VALUE
            for (i in 0 until f) {
                val prev = row[i]
                if (prev < run) run = prev
                // Small touch noise is not evidence against an extra collinear letter.
                // E.g. automation and automaton share the same ideal path; use frequency.
                row[i] = maxOf(0f, keyDist2[b + i] - 0.16f) + run
            }
        }
        var best = Float.MAX_VALUE
        for (i in 0 until f) if (row[i] < best) best = row[i]
        return best
    }

    // ------------------------------------------------------------------------------------------
    // Top-k bookkeeping.
    // ------------------------------------------------------------------------------------------

    private fun resetBest(limit: Int) {
        if (bestCost.size < limit) {
            bestCost = FloatArray(limit)
            bestWord = arrayOfNulls(limit)
        }
        bestCount = 0
        bestLimit = limit
    }

    private fun offer(word: String, cost: Float) {
        for (i in 0 until bestCount) {
            if (bestWord[i] == word) {
                if (cost >= bestCost[i]) return
                // Remove the worse duplicate, then insert normally.
                for (j in i until bestCount - 1) {
                    bestCost[j] = bestCost[j + 1]
                    bestWord[j] = bestWord[j + 1]
                }
                bestCount--
                bestWord[bestCount] = null
                break
            }
        }
        if (bestCount == bestLimit && cost >= bestCost[bestCount - 1]) return
        var pos = if (bestCount < bestLimit) bestCount else bestCount - 1
        while (pos > 0 && bestCost[pos - 1] > cost) {
            bestCost[pos] = bestCost[pos - 1]
            bestWord[pos] = bestWord[pos - 1]
            pos--
        }
        bestCost[pos] = cost
        bestWord[pos] = word
        if (bestCount < bestLimit) bestCount++
    }

    // ------------------------------------------------------------------------------------------
    // Layout helpers.
    // ------------------------------------------------------------------------------------------

    private fun setLayout(centers: Map<Char, Pt>) {
        if (centers == layoutSrc) return
        // Only letter keys take part: punctuation keys (' , . -) that happen to be on the layout are
        // ignored, so they never become start/end keys and "don't" decodes from d-o-n-t.
        val entries = centers.entries
            .filter { it.key.isLetter() }
            .filter { !it.value.x.isNaN() && !it.value.y.isNaN() && !it.value.x.isInfinite() && !it.value.y.isInfinite() }
            .map { it.key.lowercaseChar() to it.value }
            .distinctBy { it.first }
            .sortedBy { it.first }
        keyCount = entries.size
        keyChars = CharArray(keyCount) { entries[it].first }
        keyPxX = FloatArray(keyCount) { entries[it].second.x }
        keyPxY = FloatArray(keyCount) { entries[it].second.y }
        keyX = FloatArray(keyCount)
        keyY = FloatArray(keyCount)
        isStartKey = BooleanArray(keyCount)
        isEndKey = BooleanArray(keyCount)
        layoutSrc = HashMap(centers)
    }

    private fun keyIndexOf(c: Char): Int {
        var lo = 0
        var hi = keyCount - 1
        val chars = keyChars
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = chars[mid]
            when {
                v < c -> lo = mid + 1
                v > c -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /**
     * Fills [out] with up to [MAX_END_KEYS] keys within [END_RADIUS] of (x, y), nearest first.
     * Falls back to the nearest two keys if none is that close. Returns the count.
     */
    private fun nearestKeys(x: Float, y: Float, out: IntArray): Int {
        val k = keyCount
        val d = FloatArray(k)
        for (i in 0 until k) {
            val dx = keyX[i] - x
            val dy = keyY[i] - y
            d[i] = sqrt(dx * dx + dy * dy)
        }
        val order = (0 until k).sortedBy { d[it] }
        if (d[order[0]] > END_RADIUS) {
            val count = minOf(FALLBACK_END_KEYS, k, out.size)
            for (i in 0 until count) out[i] = order[i]
            return count
        }
        var count = 0
        for (idx in order) {
            if (count == out.size || d[idx] > END_RADIUS) break
            out[count++] = idx
        }
        return count
    }

    companion object {
        /** Points per resampled path for the shape channel. */
        const val SAMPLES = 64

        /** Max distance (key widths) from the first/last touch to a start/end key. */
        const val END_RADIUS = 1.1f

        /** Max number of start (or end) keys considered. */
        const val MAX_END_KEYS = 4

        /** If no key is within [END_RADIUS], this many nearest keys are used instead. */
        const val FALLBACK_END_KEYS = 2

        /** A letter "was passed" if the path comes within this many key widths of its centre. */
        const val PASS_RADIUS = 1.2f
        private const val CORNER_RADIUS = 1.6f

        /** Spacing (key widths) of the dense filter path. */
        const val FILTER_STEP = 0.2f
        const val MAX_FILTER_POINTS = 512

        /** Candidates whose mean squared shape deviation exceeds this (key widths²) are rejected. */
        const val MAX_SHAPE = 9.0f

        /** Weight of the shape channel (mean squared deviation from the ideal path). */
        const val W_SHAPE = 2.0f

        /** Weight of the location channel (sum of squared key-to-path distances). */
        const val W_LOC = 0.5f

        /** Weight of the compressed dictionary frequency prior (255 - freq) / 32. */
        const val W_FREQ = 0.2f

        private fun polylineLength(xs: FloatArray, ys: FloatArray, n: Int): Float {
            var total = 0f
            for (i in 1 until n) {
                val dx = xs[i] - xs[i - 1]
                val dy = ys[i] - ys[i - 1]
                total += sqrt(dx * dx + dy * dy)
            }
            return total
        }

        /** Resamples the polyline (srcX, srcY, srcN) of length [total] to [n] equidistant points. */
        private fun resample(
            srcX: FloatArray, srcY: FloatArray, srcN: Int, total: Float,
            n: Int, outX: FloatArray, outY: FloatArray,
        ) {
            if (srcN == 1 || total <= 1e-6f) {
                outX.fill(srcX[0], 0, n)
                outY.fill(srcY[0], 0, n)
                return
            }
            val step = total / (n - 1)
            outX[0] = srcX[0]
            outY[0] = srcY[0]
            var seg = 1
            var acc = 0f // path length at srcN[seg - 1]
            var segLen = dist(srcX, srcY, 0, 1)
            for (k in 1 until n - 1) {
                val target = k * step
                while (seg < srcN - 1 && acc + segLen < target) {
                    acc += segLen
                    seg++
                    segLen = dist(srcX, srcY, seg - 1, seg)
                }
                val t = if (segLen > 0f) ((target - acc) / segLen).coerceIn(0f, 1f) else 0f
                outX[k] = srcX[seg - 1] + (srcX[seg] - srcX[seg - 1]) * t
                outY[k] = srcY[seg - 1] + (srcY[seg] - srcY[seg - 1]) * t
            }
            outX[n - 1] = srcX[srcN - 1]
            outY[n - 1] = srcY[srcN - 1]
        }

        private fun dist(xs: FloatArray, ys: FloatArray, a: Int, b: Int): Float {
            val dx = xs[b] - xs[a]
            val dy = ys[b] - ys[a]
            return sqrt(dx * dx + dy * dy)
        }
    }
}
