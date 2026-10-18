// EverySync Hub — todos -> Reminders sink (no Todo app).
//
// `EKEventStore` + `requestAccess(to: .reminder)`; synced tasks live in a
// dedicated hub-managed `EKCalendar` (or the default list) and appear in the
// system Reminders app. Field mapping (verbatim from `Hub/README.md`):
//
// | `RemoteTodo`            | `EKReminder`                          |
// | `uid`                   | `calendarItemIdentifier` via the hub's syncId<->identifier map (identifiers are hub-local state, NOT IPC columns) |
// | `title`                 | `title`                               |
// | `notes`                 | `notes` (MUST persist — merge-hash input) |
// | `done`                  | `completed` (+ `completionDate`)      |
// | `dueAt` (epoch ms, nullable) | `dueDateComponents` (via `Calendar`; nil clears) |
// | `position`              | not mapped — sort client-side (excluded from the merge hash, so reorder is never an edit) |
// | `createdAt`             | read-only reference (part of the adoption content key; never written back) |
// | `deleted=true` envelope | remove the `EKReminder`               |
//
// Tombstone mapping: `EKReminder` has no tombstone — a user-deleted reminder
// simply vanishes from the store. The hub therefore persists the set of known
// syncIds per vault (`SyncIdMap.swift`); after a HEALTHY fetch, any known
// syncId with no matching `EKReminder` is a genuine local delete and folds
// into the next merge as a tombstone envelope. Merge-driven deletes always
// arrive as tombstone envelopes, never raw deletes. Same healthy-read
// discipline as `SyncSink.listLocalOk`: an unhealthy fetch aborts the cycle
// instead of reading absence as deletion.
//
// MAC-CHECK (needs xcodebuild on a Mac):
// - `EKEventStore(requestAccessToEntityType:completion:)` vs iOS 17+
//   `requestFullAccessToReminders` — confirm the right call per deployment target.
// - `EKReminder` due-date bridging (`dueDateComponents` <-> epoch ms) with
//   explicit `Calendar(identifier: .gregorian)` + UTC to avoid DST drift.

import EventKit
import EverysyncCore

/// Applies XCFramework driver output for one vault's todos to Reminders.
/// Driver output in, `EKReminder` rows out — no merge/hash/crypto here.
final class TodoReminderSink {
    private let eventStore: EKEventStore
    private let syncIdMap: SyncIdMap
    private let calendar = Calendar(identifier: .gregorian)

    init(eventStore: EKEventStore, syncIdMap: SyncIdMap) {
        self.eventStore = eventStore
        self.syncIdMap = syncIdMap
    }

    // TODO(Mac): implement against the generated XCFramework `RemoteTodo` type:
    //
    // func apply(vaultId: String, envelopes: [RemoteTodo], targetCalendar: EKCalendar) throws:
    //   - for each envelope with deleted == true: look up the EKReminder via
    //     syncIdMap; `eventStore.remove(reminder, commit: false)`; drop the
    //     syncId from the known set (tombstone acknowledged).
    //   - else: fetch-or-create EKReminder in targetCalendar; set
    //     title/notes/completed+completionDate/dueDateComponents
    //     (dueAt ms -> DateComponents via `calendar.dateComponents(from: Date(...))`;
    //     nil clears); `position` ignored; `createdAt` never written back.
    //     Record syncId -> calendarItemIdentifier in syncIdMap.
    //   - commit once at the end (`eventStore.commit()`); a commit failure =
    //     unhealthy write — report up so the cycle does NOT advance cursors.
    //
    // func detectLocalDeletes(vaultId: String, targetCalendar: EKCalendar,
    //                        knownSyncIds: Set<String>) throws -> Set<String>:
    //   - HEALTHY fetch of reminders in targetCalendar first; on ANY fetch
    //     failure THROW (caller aborts — never read absence as deletion).
    //   - return knownSyncIds minus fetched identifiers: genuine local
    //     deletes, which the driver folds into tombstone envelopes.
    //   - `notes` + `createdAt` are never compared here (they ride the
    //     envelope); only presence/absence after a healthy read matters.

    /// Epoch-ms <-> DateComponents conversion. UTC Gregorian; nil clears.
    func dueDateComponents(fromDueAt dueAt: Int64?) -> DateComponents? {
        guard let dueAt else { return nil }
        var utc = calendar
        utc.timeZone = TimeZone(identifier: "UTC") ?? .current
        return utc.dateComponents(
            [.year, .month, .day, .hour, .minute, .second],
            from: Date(timeIntervalSince1970: TimeInterval(dueAt) / 1000.0)
        )
    }

    func dueAt(from components: DateComponents?) -> Int64? {
        guard let components,
              let date = calendar.date(from: components) else { return nil }
        return Int64((date.timeIntervalSince1970 * 1000.0).rounded())
    }
}
