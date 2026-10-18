package com.vayunmathur.desktop.notes.systemsinks.linux

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * Evolution Data Server (EDS) native bridge for the Linux system sinks.
 *
 * **STUB STATUS — file-backed stores are the active backend.** This object
 * declares the real `libecal-2.0` / `libebook-2.0` C entry points the future
 * native path will call (signatures below are the genuine EDS C signatures
 * with GObject handles as opaque [Pointer]s), plus [isAvailable] to probe
 * for a linkable lib. The CI/Windows environment cannot link EDS, so
 * [isAvailable] returns false there and every sink in this package persists
 * to its file mirror instead — with the exact EDS field mapping documented in
 * that sink's KDoc so the native swap changes storage, never semantics.
 *
 * Wiring the native path (needs a Linux device/CI run with
 * `libecal-2.0` + `libebook-2.0` + GObject introspection):
 *  1. `Native.load("ecal-2.0", LibECal::class.java)` succeeds ([isAvailable]).
 *  2. Open the collection's [Pointer] client (`e_cal_client_new` /
 *     `e_book_client_new` on the account's `ESource`).
 *  3. Replace each sink's file read/write with the list/create/modify/remove
 *     calls below, keeping the (uid, stamp, vault) triple and the tombstone
 *     discipline identical.
 */
object EdsBridge {

    private const val LIB_ECAL = "ecal-2.0"
    private const val LIB_EBOOK = "libebook-2.0"

    @Volatile private var availability: Boolean? = null

    /**
     * True when both EDS client libs load in this process. False on
     * CI/Windows (no EDS) — sinks then use their file mirrors. Never throws:
     * a failed load is a normal "no native provider" signal, not an error.
     */
    fun isAvailable(): Boolean {
        availability?.let { return it }
        val ok = runCatching {
            Native.load(LIB_ECAL, LibECal::class.java)
            Native.load(LIB_EBOOK, LibEBook::class.java)
            true
        }.getOrDefault(false)
        availability = ok
        return ok
    }

    /**
     * STUB — JNA shape of the `libecal-2.0` calls the calendar + task-list
     * sinks will use. Real C signatures (GObject pointers opaque here):
     * - `ECalClient *e_cal_client_new(ESource *source, ECalClientSourceType source_type, GError **error)`
     * - `gboolean e_cal_client_get_object_list_sync(ECalClient *client, const gchar *sexp, GCancellable *cancellable, GSList **objects, GError **error)`
     * - `gboolean e_cal_client_create_objects_sync(ECalClient *client, const GSList *icalcomps, GCancellable *cancellable, GSList **uids, ECalOperationFlags flags, GError **error)`
     * - `gboolean e_cal_client_modify_objects_sync(ECalClient *client, const GSList *icalcomps, ECalObjModType mod, ECalOperationFlags flags, GCancellable *cancellable, GError **error)`
     * - `gboolean e_cal_client_remove_objects_sync(ECalClient *client, const GSList *ids, ECalObjModType mod, ECalOperationFlags flags, GCancellable *cancellable, GError **error)`
     *
     * `source_type` is `E_CAL_CLIENT_SOURCE_TYPE_EVENTS` (calendar) or
     * `E_CAL_CLIENT_SOURCE_TYPE_TASKS` (task list); `sexp` filters
     * (`"#t"` = all objects); components ride as `ECalComponent`/`ICalComponent`
     * pointers wrapping the same iCalendar text `core.format.ICalendar` emits.
     */
    interface LibECal : Library {
        fun e_cal_client_new(source: Pointer, sourceType: Int, error: PointerByReference): Pointer?
        fun e_cal_client_get_object_list_sync(
            client: Pointer,
            sexp: String,
            cancellable: Pointer?,
            objects: PointerByReference,
            error: PointerByReference,
        ): Boolean
        fun e_cal_client_create_objects_sync(
            client: Pointer,
            icalcomps: Pointer,
            cancellable: Pointer?,
            uids: PointerByReference,
            flags: Int,
            error: PointerByReference,
        ): Boolean
        fun e_cal_client_modify_objects_sync(
            client: Pointer,
            icalcomps: Pointer,
            mod: Int,
            flags: Int,
            cancellable: Pointer?,
            error: PointerByReference,
        ): Boolean
        fun e_cal_client_remove_objects_sync(
            client: Pointer,
            ids: Pointer,
            mod: Int,
            flags: Int,
            cancellable: Pointer?,
            error: PointerByReference,
        ): Boolean
    }

    /**
     * STUB — JNA shape of the `libebook-2.0` calls the contacts sink will use.
     * Real C signatures:
     * - `EBookClient *e_book_client_new(ESource *source, GError **error)`
     * - `gboolean e_book_client_get_contacts_sync(EBookClient *client, const gchar *sexp, GSList **contacts, GCancellable *cancellable, GError **error)`
     * - `gboolean e_book_client_add_contact_sync(EBookClient *client, EContact *contact, GCancellable *cancellable, gchar **added_uid, GError **error)`
     * - `gboolean e_book_client_modify_contact_sync(EBookClient *client, EContact *contact, GCancellable *cancellable, GError **error)`
     * - `gboolean e_book_client_remove_contact_sync(EBookClient *client, const gchar *uid, GCancellable *cancellable, GError **error)`
     *
     * Contacts ride as `EContact` pointers wrapping the same vCard text
     * `core.format.VCard` emits; `E_CONTACT_UID` is the sync identity.
     */
    interface LibEBook : Library {
        fun e_book_client_new(source: Pointer, error: PointerByReference): Pointer?
        fun e_book_client_get_contacts_sync(
            client: Pointer,
            sexp: String,
            contacts: PointerByReference,
            cancellable: Pointer?,
            error: PointerByReference,
        ): Boolean
        fun e_book_client_add_contact_sync(
            client: Pointer,
            contact: Pointer,
            cancellable: Pointer?,
            addedUid: PointerByReference,
            error: PointerByReference,
        ): Boolean
        fun e_book_client_modify_contact_sync(
            client: Pointer,
            contact: Pointer,
            cancellable: Pointer?,
            error: PointerByReference,
        ): Boolean
        fun e_book_client_remove_contact_sync(
            client: Pointer,
            uid: String,
            cancellable: Pointer?,
            error: PointerByReference,
        ): Boolean
    }

    /** `ESource` type selectors for [LibECal.e_cal_client_new]. */
    object SourceType {
        const val EVENTS = 0 // E_CAL_CLIENT_SOURCE_TYPE_EVENTS
        const val TASKS = 1 // E_CAL_CLIENT_SOURCE_TYPE_TASKS
    }
}
