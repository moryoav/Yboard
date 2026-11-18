package com.yboard.keyboard.text

/** Geometry constrains the gesture; only Qwen supplies the linguistic preference. */
object GlideRanking {
    fun rank(candidates: List<SwipeDecoder.Candidate>, logProbabilities: DoubleArray): List<String> {
        require(candidates.size == logProbabilities.size)
        val bestShape = candidates.minOfOrNull { it.cost } ?: return emptyList()
        return candidates.indices.filter { candidates[it].cost.isFinite() && logProbabilities[it].isFinite() && candidates[it].cost <= bestShape + 3.0f }
            // A clear letter detour must outweigh a common but shorter word.
            // Qwen resolves paths whose geometry is actually ambiguous.
            .sortedBy { 12.0 * candidates[it].cost - logProbabilities[it] }
            .map { candidates[it].word }.distinct().take(4)
    }
}
