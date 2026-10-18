// EverySync Hub — Phase 3 sync-hub shell.
//
// ZERO sync logic in Swift. All merge (`TodoKind` / `ContactKind` /
// `CalendarKind`), transport (`IosRelayTransport`), and crypto lives in the
// shared XCFramework. This target:
// - runs `SyncDriver` cycles on a schedule (BGTask — see
//   `../Shared/BackgroundSync.swift`),
// - owns the App-Group envelope store (see `../IPC_CONTRACT.md`),
// - sinks todos / contacts / calendar into the system stores
//   (Reminders via `EKReminder`, Contacts via `CNContactStore`, Calendar via
//   `EKEvent`).
// There is NO Todo UI: tasks surface in the system Reminders app.
//
// Driver output in, system stores out — nothing else.

import EventKit
import Contacts
import BackgroundTasks
import EverysyncCore

// MARK: - KMP entry points (Phase 3 wiring — call, never re-implement)
//
// data:        core.data.RemoteTodo (uid / title / notes / done / dueAt epoch
//                ms nullable / position / createdAt / deleted / updatedAt)
//              core.data.RemoteContact (name parts + organization + note +
//                phones/emails/addresses TypedValue(value, type) + ISO-8601
//                birthday + etag/href/deleted/updatedAt)
//              core.data.RemoteEvent (calendarId / summary / description /
//                location / startMillis / endMillis / allDay / timezone /
//                rrule verbatim no-RRULE-prefix / recurrenceId / etag / href /
//                deleted / updatedAt)
// kinds:       core.sync.adapters.TodoKind (syncId=uid, LWW updatedAt,
//                canonical strips identity/position/timestamps — reorder is
//                not an edit; `contentKey = "todo␀" + title + "␀" + createdAt`;
//                soft-delete; `notes`+`createdAt` MUST persist — both are
//                merge-hash inputs, dropping them breaks LWW convergence)
//              core.sync.adapters.ContactKind (syncId=uid; canonical vCard
//                strips identity/transport metadata)
//              core.sync.adapters.CalendarKind (syncId = UID [+ ␀RECURRENCE-ID
//                for overridden instances]; canonical iCal keeps RECURRENCE-ID)
// driver:      core.provider.SyncDriver.drive(kind, store, remote, append, now)
//                — contract: null remote ABORTS (never merges); `remoteOut`
//                appends to the relay BEFORE persisting locally.
// stores:      RelayStore shims over the envelope IPC (see
//              `IPCRelayStore.swift` + `../XCFramework/IOS_ACTUALS_NOTES.md`)
// cursors:     core.platform.RelayCursorStore
// securestore: core.platform.SecureStore (Keychain actual)
// transport:   core.network.IosRelayTransport
// channels:    recordChannel / membersChannel / keysChannel / ownerChannel /
//              titleChannel / inboxChannel / blobChannel (`notes-blobs:<vault>`)
// roles:       RelayRoles — passwords offer EDITOR only, never VIEWER
// links:       RelayLinks.parseJoinLink / .shareLinkFor
// pqc:         exactly 10 fns per IOS_PQC_NOTES.md (e2eePqc cinterop)

/// Sync hub: runs XCFramework `SyncDriver` cycles, owns the App-Group envelope
/// store, and sinks todos/contacts/calendar into the system stores.
/// Scheduling + mapping only.
final class HubSync {
    let eventStore = EKEventStore()
    let contactStore = CNContactStore()
    private let ipc = EnvelopeIPC()
    private let syncIdMap = SyncIdMap()

    init() {
        // MAC-CHECK: identifiers must match Info.plist
        // `BGTaskSchedulerPermittedIdentifiers`.
        BackgroundSync.register(handlers: [
            "com.vayunmathur.everysync.hub.refresh": { task in
                // TODO(Mac): run SyncDriver cycles for every vault (todos +
                // contacts + calendar + hub-side passwords/notes relay ops),
                // then re-schedule + `task.setTaskCompleted(success:)`.
                task.setTaskCompleted(success: true)
            },
            "com.vayunmathur.everysync.hub.upload": { task in
                // TODO(Mac): one-shot after local system-store changes
                // (EKEventStore notifications); push deltas, then complete.
                task.setTaskCompleted(success: true)
            },
        ])
    }

    /// Requests Reminders / Calendar / Contacts access, then starts cycles.
    /// Each grant is independent: a denied store only disables its sink —
    /// the hub still syncs the other types (never abort everything on one
    /// denial; each sink tracks its own `listLocalOk`-equivalent health).
    func requestSystemAccess() {
        // TODO(Mac): `eventStore.requestAccess(to: .reminder)`,
        // `eventStore.requestAccess(to: .event)` (iOS 17+: `requestFullAccess-`
        // variant — confirm on the Mac), `contactStore.requestAccess(for:
        // .contacts)`, then start SyncDriver cycles per granted store.
    }

    /// Drives one todos cycle for a vault: pull `todos:<vaultId>` via the
    /// XCFramework, apply through `TodoReminderSink`, persist known syncIds.
    /// See `TodoReminderSink.swift` for the field + tombstone mapping.
    func syncTodosToReminders(vaultId: String) {
        // TODO(Mac): call into the XCFramework SyncDriver with the
        // `IPCRelayStore<RemoteTodo>` shim + `TodoReminderSink` application.
    }

    /// Drives one contacts cycle for a vault. SyncId<->identifier map stays
    /// hub-local (see `SyncIdMap.swift`); Swift never scribbles syncIds into
    /// user-visible contact fields.
    func syncContacts(vaultId: String) {
        // TODO(Mac): SyncDriver cycle with the contacts IPC shim + ContactSink.
    }

    /// Drives one calendar cycle for a vault into the hub-managed calendar.
    func syncCalendarEvents(vaultId: String) {
        // TODO(Mac): SyncDriver cycle with the calendar IPC shim + CalendarSink.
    }
}
