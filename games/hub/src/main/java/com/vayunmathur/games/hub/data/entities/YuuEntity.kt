package com.vayunmathur.games.hub.data.entities

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * One named Yuu avatar in the plaza collection.
 *
 * The face itself is a [YuuFaceConfig] serialized to [configJson] (see YuuJson); the
 * table stays flat so there is no per-part table. [isProfile] marks the Yuu currently
 * linked as the hub profile avatar — at most one row holds true.
 */
@Entity(tableName = "yuus")
data class YuuEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String = "",
    val configJson: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val isProfile: Boolean = false,
)
