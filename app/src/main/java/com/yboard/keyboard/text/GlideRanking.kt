package com.yboard.keyboard.text

/** Combines gesture geometry and model scores with an optional personal tie-breaker. */
object GlideRanking {
    fun rank(candidates: List<SwipeDecoder.Candidate>, logProbabilities: DoubleArray, user: UserWords? = null): List<String> {
        require(candidates.size == logProbabilities.size)
        val bestShape = candidates.minOfOrNull { it.cost } ?: return emptyList()
        return candidates.indices.filter { candidates[it].cost.isFinite() && logProbabilities[it].isFinite() && candidates[it].cost <= bestShape + 3.0f }
            .map { index ->
                val (word, cost) = candidates[index]
                val score = logProbabilities[index] - 12.0 * cost
                val uses = user?.count(Dictionary.normalize(word)) ?: 0
                ScoredWord(word, score + UserWords.rankingBonus(uses))
            }
            .sortedByDescending { it.score }
            .map { it.word }.distinct().take(4)
    }
}
