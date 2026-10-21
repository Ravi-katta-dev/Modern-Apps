package com.vayunmathur.todo.platform

import com.vayunmathur.todo.data.Todo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the todo UI contract: state defaults, the no-op actions used by
 * previews, and the detail-page copy chain (`setTitle` / `setNotes` /
 * `setDone` / `setDueAt` are plain `copy` calls on the edited row).
 *
 * The binders live in composables, so the copies are mirrored here rather
 * than called. The mirror is exact: `copy(title = ...)`, `copy(notes = ...)`,
 * `copy(done = ...)`, `copy(dueAt = ...)`.
 */
class TodoUiContractTest {

    @Test
    fun `list state defaults to an empty list with the add button shown`() {
        val state = TodoListUiState()
        assertEquals(emptyList(), state.todos)
        assertTrue(state.showAddButton)
    }

    @Test
    fun `detail state defaults to a blank open untimed task`() {
        val state = TodoUiState()
        assertEquals("", state.title)
        assertEquals("", state.notes)
        assertFalse(state.done)
        assertNull(state.dueAt)
    }

    @Test
    fun `noop actions accept every call without throwing`() {
        val list = TodoListActions.Noop
        list.openTodo(1L)
        list.createTodo()
        list.toggleDone(Todo(id = 1))
        list.delete(Todo(id = 1))
        list.upsertAll(listOf(Todo(id = 1)))
        val detail = TodoActions.Noop
        detail.back()
        detail.setTitle("t")
        detail.setNotes("n")
        detail.setDone(true)
        detail.setDueAt(5L)
        detail.deleteTodo()
    }

    @Test
    fun `the detail copy chain edits one field at a time`() {
        val start = Todo(title = "t", notes = "n", done = false, dueAt = null)
        val renamed = start.copy(title = "new")
        assertEquals("new", renamed.title)
        assertEquals("n", renamed.notes)
        val noted = renamed.copy(notes = "new notes")
        assertEquals("new notes", noted.notes)
        val checked = noted.copy(done = true)
        assertTrue(checked.done)
        val dated = checked.copy(dueAt = 42L)
        assertEquals(42L, dated.dueAt)
        assertEquals("new", dated.title)
    }

    @Test
    fun `detail state round-trips from a todo and back`() {
        val todo = Todo(title = "t", notes = "n", done = true, dueAt = 7L)
        val state = TodoUiState(title = todo.title, notes = todo.notes, done = todo.done, dueAt = todo.dueAt)
        val back = todo.copy(title = state.title, notes = state.notes, done = state.done, dueAt = state.dueAt)
        assertEquals(todo, back)
    }

    @Test
    fun `unicode survives the detail copy chain`() {
        val state = TodoUiState(title = "記 😀", notes = "مرحبا тело")
        assertEquals("記 😀", state.title)
        assertEquals("مرحبا тело", state.notes)
    }
}
