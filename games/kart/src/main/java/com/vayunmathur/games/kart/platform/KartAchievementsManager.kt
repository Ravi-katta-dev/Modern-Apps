package com.vayunmathur.games.kart.platform

import android.content.Context
import com.vayunmathur.games.kart.data.KartDataStore
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DailyChallengeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class KartAchievementsManager(
    context: Context,
    json: String,
    private val dataStore: KartDataStore,
    private val dailyStore: DailyChallengeStore,
) : AchievementsManager(context, json) {

    /**
     * Back-fills from stored progress on launch, so an achievement added in a later release is awarded
     * for work the player has already done rather than only for the next race they finish.
     *
     * The reads are suspending, so this hands off to a coroutine rather than blocking.
     */
    override fun checkExistingAchievements() {
        CoroutineScope(Dispatchers.IO).launch {
            val total = dataStore.cupsCleared.first()
            if (total > 0) onAchievementUnlocked("first_race")
            onProgressUpdated("cups_3", total)

            if (dailyStore.lastCompletedDay() != DailyChallengeStore.NO_DAY) {
                onAchievementUnlocked("first_daily")
            }
            val best = dailyStore.bestStreak().toInt()
            onProgressUpdated("daily_streak_7", best)
            onProgressUpdated("daily_streak_30", best)
        }
    }
}
