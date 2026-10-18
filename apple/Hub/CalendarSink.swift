// EverySync Hub — calendar -> `EKEvent` sink.
//
// `EKEventStore` + calendar access. Maps `RemoteEvent` summary / description /
// location / start-end millis / allDay / timezone / RRULE / recurrenceId onto
// `EKEvent` in a hub-managed calendar. Same driver-applies, Swift-renders
// split as the other sinks: delete and conflict semantics follow
// `CalendarKind` (syncId = UID [+ ␀RECURRENCE-ID for overridden instances];
// canonical iCal keeps RECURRENCE-ID; soft-delete).
//
// Field mapping:
// - `uid` (+ `recurrenceId` for overridden instances) -> identity via the
//   hub map (`SyncIdMap.swift`; the composite `uid␀recurrenceId` is the map
//   key — `EKEvent.eventIdentifier` is hub-local state, never an IPC column).
// - `summary/description/location` -> `title/notes/location`.
// - `startMillis/endMillis` -> `startDate/endDate` (UTC instant; `timeZone`
//   sets `event.timeZone` for display — the stored instants never shift).
// - `allDay` -> `isAllDay`.
// - `rrule` (raw RRULE line WITHOUT the `RRULE:` prefix) -> `recurrenceRules`
//   (`EKRecurrenceRule` parsed from the verbatim line; masters store duration
//   instead of end — same as `core.format.ICalendar`).
// - `recurrenceId` -> detached occurrence (`event.recurrenceIdentifier`
//   lineage; each overridden instance merges independently).
// - `deleted=true` envelope -> remove the `EKEvent`.
// - `calendarId` selects the hub-managed calendar (NOT a system calendar id —
//   hub calendars are created per vault and recorded in the syncId map
//   namespace).
//
// MAC-CHECK (needs xcodebuild on a Mac):
// - `EKRecurrenceRule` init from an RFC 5545 RRULE line: EventKit has no
//   string parser — the Mac must map the verbatim line through
//   `EKRecurrenceRule(recurrenceWith:interval:end:)` field-by-field
//   (FREQ/INTERVAL/COUNT/UNTIL/BYDAY/…). Keep that parser next to this file
//   (`RRuleParser.swift`); it is display-mapping, NOT merge logic.
// - `requestFullAccessToEvents` vs legacy `requestAccess(to: .event)` per
//   deployment target.

import EventKit
import EverysyncCore

/// Applies XCFramework driver output for one vault's events.
/// Driver output in, `EKEvent` rows out — nothing else.
final class CalendarSink {
    private let eventStore: EKEventStore
    private let syncIdMap: SyncIdMap

    init(eventStore: EKEventStore, syncIdMap: SyncIdMap) {
        self.eventStore = eventStore
        self.syncIdMap = syncIdMap
    }

    // TODO(Mac): implement against the generated XCFramework `RemoteEvent` type:
    //
    // func apply(vaultId: String, envelopes: [RemoteEvent], targetCalendar: EKCalendar) throws:
    //   - map key = recurrenceId.isBlank ? uid : "uid␀recurrenceId"
    //     (matches `CalendarKind.syncId` — keep the separator ␀ U+0000).
    //   - deleted == true -> remove mapped EKEvent; drop the map entry.
    //   - else fetch-or-create EKEvent in targetCalendar; set title/notes/
    //     location/startDate/endDate/isAllDay/timeZone; recurrenceRules from
    //     the RRULE line via RRuleParser (nil line clears rules on masters;
    //     overridden instances keep the master's rules + their RECURRENCE-ID).
    //   - commit once; commit failure = unhealthy write (don't advance cursors).
    //
    // func detectLocalDeletes(knownKeys: Set<String>, targetCalendar: EKCalendar,
    //                        window: (start: Date, end: Date)) throws -> Set<String>:
    //   - HEALTHY predicate fetch in the window first; throw on ANY failure.
    //   - known minus fetched = genuine local deletes -> tombstone envelopes.
    //   - Windowed-fetch caveat: only keys whose events fall inside the window
    //     are eligible for delete detection (older events need a wider fetch —
    //     document the window in E2E_CHECKLIST.md).

    /// Composite sync key (matches `CalendarKind.syncId` exactly).
    static func syncKey(uid: String, recurrenceId: String?) -> String {
        guard let recurrenceId, !recurrenceId.isEmpty else { return uid }
        return "\(uid)\u{0000}\(recurrenceId)"
    }
}
