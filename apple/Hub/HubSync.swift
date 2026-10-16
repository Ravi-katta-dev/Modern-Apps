// EverySync Hub — Phase 3a sync-hub shell.
//
// Zero sync logic in Swift. All merge / transport / crypto lives in the
// shared XCFramework (everysync-core KMP). This file only declares the hub
// entry point — SyncDriver scheduling plus EKReminder / CNContact / EKEvent
// sinks — and, in comments, the exact KMP entry points Phase 3 will call.
// Nothing here merges, hashes, or encrypts. There is no Todo UI: tasks
// surface in the system Reminders app.

import EventKit
import Contacts
import EverysyncCore

// MARK: - KMP entry points (Phase 3 wiring, for reference only)
//
// data:        com.vayunmathur.everysync.core.data.RemoteTodo
//              com.vayunmathur.everysync.core.data.RemoteContact
//              com.vayunmathur.everysync.core.data.RemoteEvent
// kinds:       com.vayunmathur.everysync.core.sync.adapters.TodoKind
//              (channel "todos:<vaultId>"; soft-delete; reorder is not an edit)
// driver:      com.vayunmathur.everysync.core.provider.SyncDriver
// cursors:     com.vayunmathur.everysync.core.platform.RelayCursorStore
// securestore: com.vayunmathur.everysync.core.platform.SecureStore (Keychain actual)
// transport:   com.vayunmathur.everysync.core.network.IosRelayTransport
// channels:    recordChannel / membersChannel / keysChannel / ownerChannel
//              / titleChannel / inboxChannel / blobChannel ("notes-blobs:<vaultId>")
// roles:       RelayRoles — passwords offer EDITOR only, never VIEWER
// links:       RelayLinks.parseJoinLink / .shareLinkFor
// pqc:         10 fns per IOS_PQC_NOTES.md (e2eePqc cinterop)

/// Sync hub: runs XCFramework SyncDriver cycles, owns the App-Group envelope
/// store (see ../IPC_CONTRACT.md), and sinks todos/contacts/calendar into
/// the system stores. Scheduling + mapping only.
final class HubSync {
    let eventStore = EKEventStore()
    let contactStore = CNContactStore()

    // TODO(Phase 3): create SyncDriver + IosRelayTransport from the
    //  XCFramework; open the App-Group envelope store; register
    //  BGTaskScheduler handlers (see README.md).

    func requestSystemAccess() {
        // TODO(Phase 3): eventStore.requestAccess(to: .reminder),
        //  eventStore.requestAccess(to: .event),
        //  contactStore.requestAccess(for: .contacts),
        //  then start SyncDriver cycles.
    }

    func syncTodosToReminders(vaultId: String) {
        // TODO(Phase 3): pull "todos:<vaultId>" via SyncDriver; upsert
        //  EKReminder per the README mapping table; persist known syncIds so
        //  a vanished EKReminder folds back as a tombstone envelope.
    }

    func syncContacts(vaultId: String) {
        // TODO(Phase 3): apply SyncDriver contact output to CNContactStore;
        //  keep the syncId<->identifier map hub-local.
    }

    func syncCalendarEvents(vaultId: String) {
        // TODO(Phase 3): apply SyncDriver event output to EKEventStore in the
        //  hub-managed calendar.
    }
}
