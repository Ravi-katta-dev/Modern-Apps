package com.vayunmathur.todo.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.ReorderableDatabaseItem
import kotlinx.serialization.Serializable

@Serializable
@Entity
data class Todo(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val title: String = "",
    val notes: String = "",
    val done: Boolean = false,
    override val position: Double = 0.0,
    /** Epoch ms the task is due, or null for no due date. */
    val dueAt: Long? = null,
    /** Epoch ms the task was created; part of the relay adoption content key. */
    val createdAt: Long = System.currentTimeMillis(),
    val syncId: String = newSyncId(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
) : ReorderableDatabaseItem<Todo> {
    override fun withPosition(position: Double) = copy(position = position)
}
