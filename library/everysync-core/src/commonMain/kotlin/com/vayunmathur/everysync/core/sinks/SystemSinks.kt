package com.vayunmathur.everysync.core.sinks

import com.vayunmathur.everysync.core.data.RemoteContact
import com.vayunmathur.everysync.core.data.RemoteEvent
import com.vayunmathur.everysync.core.data.RemoteTodo
import com.vayunmathur.everysync.core.platform.SyncSink

/**
 * System-provider sink contracts for every EverySync target.
 *
 * Pure Kotlin, `expect`-free: platform actuals (Android `CalendarSink` /
 * `ContactsSink`, desktop JVM, Apple Swift/KMP) implement these interfaces
 * against their native stores. All three reuse [SyncSink], so the shared
 * [com.vayunmathur.everysync.core.provider.SyncDriver] merge cycle is
 * identical on every OS; only the store binding differs.
 *
 * Canonical interchange (shared, in `core.format`):
 * - events: [RemoteEvent] <-> iCal VEVENT (`ICalendar`; RRULE carried verbatim).
 * - contacts: [RemoteContact] <-> vCard (`VCard`; type ints mirror
 *   ContactsContract codes so Android passes them through unchanged).
 * - todos: [RemoteTodo] JSON on the `todos:<vaultId>` channel (no system
 *   interchange format; each OS maps to its native reminder row).
 *
 * Sync-identity columns (uid = relay sync id, stamp = relay updatedAt):
 * - Android calendar: `Events._SYNC_ID` = uid, `SYNC_DATA1` = stamp,
 *   `SYNC_DATA2` = vault id; writes via `CALLER_IS_SYNCADAPTER` URIs so relay
 *   writes never set `DIRTY`. Local edits surface via `DIRTY`/`DELETED`.
 * - Android contacts: `RawContacts.SOURCE_ID` = uid, `SYNC1` = stamp,
 *   `SYNC2` = vault id; same sync-adapter + `DIRTY`/`DELETED` discipline.
 * - Other targets persist the same (uid, stamp, vault) triple in the store's
 *   own sync-id fields; see the per-OS table below.
 *
 * Per-OS store backends:
 *
 * | OS      | Calendar                        | Contacts             | Todos                              |
 * |---------|---------------------------------|----------------------|------------------------------------|
 * | Android | CalendarContract provider       | ContactsContract     | todo/ app vault (no system provider) |
 * | Apple   | EventKit `EKEvent`              | `CNContactStore`     | EventKit `EKReminder`              |
 * | Linux   | EDS `libecal`                   | EDS `libebook`       | EDS task list (`libecal` todos)    |
 * | Windows | `AppointmentStore` (read-only v1) | `ContactStore` (read-only v1) | Notes-view task list (read-only v1) |
 *
 * Delete discipline (matches the merge's `EntityKind` flags): calendar and
 * contacts soft-delete (provider `DELETED` flags / tombstone rows survive in
 * `listLocal`); todos soft-delete (tombstone rows persist). Passwords are NOT
 * system sinks — they hard-delete in the app vault.
 */
interface CalendarSystemSink : SyncSink<RemoteEvent> {
    /**
     * [RemoteEvent] <-> iCal VEVENT <-> native event (`EKEvent` / `libecal` /
     * `AppointmentStore` appointment): `uid` (+ `recurrenceId` for overridden
     * instances) is the identity, `ICalendar` is the canonical text form for
     * hashing, recurring masters store `RRULE` with duration instead of end.
     */
}

interface ContactsSystemSink : SyncSink<RemoteContact> {
    /**
     * [RemoteContact] <-> vCard <-> native contact (`CNContact` / `libebook` /
     * `ContactStore` contact): `uid` is the identity, `VCard` is the canonical
     * text form for hashing, phone/email/postal type ints ride the shared
     * `PhoneKind`/`EmailKind`/`PostalKind` codes.
     */
}

interface TodoSystemSink : SyncSink<RemoteTodo> {
    /**
     * [RemoteTodo] <-> native reminder (`EKReminder` / `libecal` todo /
     * Notes-view row): `uid` is the identity, `notes` + `createdAt` MUST be
     * persisted (both are envelope fields AND merge-hash inputs — dropping
     * them breaks LWW convergence), tombstones (`deleted = true`) persist as
     * real rows. `position` is ordering-only and excluded from the merge hash.
     */
}
