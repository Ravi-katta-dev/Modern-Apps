// EverySync Apple shells — background scheduling notes.
//
// The KMP `SyncScheduler` interface (`schedulePeriodic / cancel / syncNow`)
// is implemented in KOTLIN (`IOS_ACTUALS_NOTES.md`), not here. This file is
// the Swift registration side: `BGTaskScheduler` handlers that wake the
// XCFramework's `SyncDriver` cycles. Scheduling only — cycles run in Kotlin.
//
// APNs discipline (verbatim from the plan): APNs is a PULL TRIGGER ONLY,
// never payload. A push wakes the app; the app then pulls the relay channel
// (`pullRecords` from `since` cursor) through the XCFramework. No vault data,
// keys, or envelopes ever ride the push.
//
// Identifiers (must match Info.plist `BGTaskSchedulerPermittedIdentifiers` —
/// see each app's `Info.plist.reference`):
// - Passwords: `com.vayunmathur.everysync.passwords.refresh` (app-refresh) +
//   one-shot `BGProcessingTask` after local edits.
// - Notes: `com.vayunmathur.everysync.notes.refresh` + one-shot after edits.
// - Hub: `com.vayunmathur.everysync.hub.refresh` (app-refresh) +
//   `com.vayunmathur.everysync.hub.upload` (processing, after local
//   system-store changes observed via `EKEventStore` notifications).
//
// MAC-CHECK (needs xcodebuild on a Mac):
// - `BGTaskScheduler.shared.register` identifiers match the plist.
// - `UIBackgroundModes` includes `fetch` + `processing`.
// - `BGAppRefreshTaskRequest.earliestBeginDate` throttling honored (system may
//   defer; the hub re-schedules at the END of each task — see below).

import BackgroundTasks
import Foundation

/// Registers the app's BGTask handlers. Call once at launch (before the
/// XCFramework creates its `SyncScheduler` actual — the Kotlin actual calls
/// `submit`/`cancel`, this side only handles the wakeup).
///
/// - Parameter handlers: task identifier -> wakeup closure. The closure MUST:
///   1. Create its task-specific work (call into the XCFramework driver).
///   2. Re-schedule the next refresh (system gives no repeating schedule).
///   3. Call `task.setTaskCompleted(success:)` exactly once.
public enum BackgroundSync {
    public static func register(handlers: [String: @Sendable @escaping (BGTask) -> Void]) {
        for (identifier, handler) in handlers {
            BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil, launchHandler: handler)
        }
    }

    /// Schedules the next periodic pull. Kotlin's `SyncScheduler.actual`
    /// calls this shape through (interval from `schedulePeriodic`).
    public static func scheduleRefresh(identifier: String, intervalMinutes: Int = 15) throws {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: TimeInterval(intervalMinutes * 60))
        try BGTaskScheduler.shared.submit(request)
    }

    /// One-shot upload after local edits (shells call this; the actual cycle
    /// still runs in the XCFramework via `SyncScheduler.syncNow`).
    public static func scheduleProcessing(identifier: String, requiresNetwork: Bool = true) throws {
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.requiresNetworkConnectivity = requiresNetwork
        try BGTaskScheduler.shared.submit(request)
    }

    public static func cancel(identifier: String) {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
    }
}
