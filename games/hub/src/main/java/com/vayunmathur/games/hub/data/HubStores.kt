package com.vayunmathur.games.hub.data

import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.DailyStreakEntity
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import com.vayunmathur.games.hub.data.entities.PlayerProfileEntity
import com.vayunmathur.games.hub.data.entities.YuuEntity
import com.vayunmathur.games.hub.data.entities.YuuFaceConfig
import com.vayunmathur.games.hub.data.entities.decodeYuuConfig
import com.vayunmathur.games.hub.data.entities.encode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Play sessions. Backed by SessionDao. */
class SessionStore internal constructor(private val repository: GamesHubRepository) {
    private val sessionDao get() = repository.sessionDaoAccess()

    suspend fun upsertSession(session: PlaySessionEntity) = sessionDao.upsert(session)
    suspend fun getSessionById(sessionId: String): PlaySessionEntity? =
        sessionDao.getBySessionId(sessionId)
    fun sessionsByGameFlow(gameId: String): Flow<List<PlaySessionEntity>> =
        sessionDao.flowByGame(gameId)
    fun allSessionsFlow(): Flow<List<PlaySessionEntity>> = sessionDao.flowAll()
    suspend fun endSession(sessionId: String, endTime: Long, durationMs: Long) =
        sessionDao.endSession(sessionId, endTime, durationMs)
    fun totalPlaytimeSessionsFlow(): Flow<Long> = sessionDao.flowTotalPlaytimeMs()
    suspend fun clearSessions() = sessionDao.clearAll()
}

/** Daily streaks. Backed by StreakDao. */
class StreakStore internal constructor(private val repository: GamesHubRepository) {
    private val streakDao get() = repository.streakDaoAccess()

    suspend fun upsertStreak(streak: DailyStreakEntity) = streakDao.upsert(streak)
    suspend fun getStreak(gameId: String): DailyStreakEntity? = streakDao.getByGame(gameId)
    fun streakByGameFlow(gameId: String): Flow<DailyStreakEntity?> = streakDao.flowByGame(gameId)
    fun allStreaksFlow(): Flow<List<DailyStreakEntity>> = streakDao.flowAll()
    suspend fun clearStreaks() = streakDao.clearAll()
}

/** Player profile. Backed by ProfileDao. */
class ProfileStore internal constructor(private val repository: GamesHubRepository) {
    private val profileDao get() = repository.profileDaoAccess()

    fun profileFlow(): Flow<PlayerProfileEntity?> = profileDao.flowProfile()
    suspend fun getProfile(): PlayerProfileEntity? = profileDao.getProfile()
    suspend fun upsertProfile(profile: PlayerProfileEntity) = profileDao.upsert(profile)
}

/** Activity feed. Backed by ActivityDao. */
class ActivityStore internal constructor(private val repository: GamesHubRepository) {
    private val activityDao get() = repository.activityDaoAccess()

    suspend fun upsertActivity(event: ActivityEventEntity) = activityDao.upsert(event)
    fun recentActivityFlow(limit: Int = 50): Flow<List<ActivityEventEntity>> =
        activityDao.flowRecent(limit)
    fun allActivityFlow(): Flow<List<ActivityEventEntity>> = activityDao.flowAll()
    fun activityByGameFlow(gameId: String, limit: Int = 20): Flow<List<ActivityEventEntity>> =
        activityDao.flowByGame(gameId, limit)
    suspend fun clearActivities() = activityDao.clearAll()
}

/** A Yuu with its face config decoded, ready for the Canvas renderer. */
data class YuuUiModel(
    val id: Long,
    val name: String,
    val config: YuuFaceConfig,
    val isProfile: Boolean,
    val updatedAt: Long,
)

private fun YuuEntity.toUiModel() = YuuUiModel(
    id = id,
    name = name,
    config = configJson.decodeYuuConfig(),
    isProfile = isProfile,
    updatedAt = updatedAt,
)

/** Yuu avatar plaza. Backed by YuuDao; config JSON encodes/decodes at this boundary. */
class YuuStore internal constructor(private val repository: GamesHubRepository) {
    private val yuuDao get() = repository.yuuDaoAccess()

    fun yuusFlow(): Flow<List<YuuUiModel>> =
        yuuDao.flowAll().map { list -> list.map { it.toUiModel() } }

    fun profileYuuFlow(): Flow<YuuUiModel?> =
        yuuDao.flowProfile().map { it?.toUiModel() }

    suspend fun getYuu(id: Long): YuuUiModel? = yuuDao.getById(id)?.toUiModel()

    suspend fun getProfileYuu(): YuuUiModel? = yuuDao.getProfile()?.toUiModel()

    suspend fun createYuu(name: String, config: YuuFaceConfig): Long =
        yuuDao.upsert(
            YuuEntity(
                name = name,
                configJson = config.encode(),
                updatedAt = System.currentTimeMillis(),
            )
        )

    suspend fun updateYuu(id: Long, name: String, config: YuuFaceConfig) {
        val current = yuuDao.getById(id) ?: return
        yuuDao.upsert(
            current.copy(
                name = name,
                configJson = config.encode(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun duplicateYuu(id: Long): Long? {
        val current = yuuDao.getById(id) ?: return null
        return yuuDao.upsert(
            current.copy(
                id = 0L,
                name = "${current.name} copy",
                updatedAt = System.currentTimeMillis(),
                isProfile = false,
            )
        )
    }

    suspend fun deleteYuu(id: Long) = yuuDao.deleteById(id)

    suspend fun setProfileYuu(id: Long) = yuuDao.setProfile(id)

    suspend fun clearProfileYuu() = yuuDao.clearProfileFlag()

    suspend fun clearYuus() = yuuDao.clearAll()
}
