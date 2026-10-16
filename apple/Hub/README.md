# EverySync Hub — Apple shell (Phase 3a)

Sync hub. **All merge / transport / crypto lives in the shared XCFramework**
(`everysync-core` KMP); this target runs `SyncDriver` cycles, owns the
App-Group envelope store, and sinks todos / contacts / calendar into the
system stores (Reminders, Contacts, Calendar). There is **no standalone Todo
app** — tasks surface in the system Reminders app, matching the plan.

## Architecture

```
HubSync ──► EverysyncCore.xcframework ──► relay
   │                  │
   │ schedules        │ merge / TOTP / PQC /
   │ cycles only      │ transport / cursors
   ▼                  ▼
App-Group envelope store ◄── Passwords / Notes shells (IPC, see ../IPC_CONTRACT.md)
   │
   ├──► EKReminder sink (todos) ──► Reminders app
   ├──► CNContact sink (contacts)
   └──► EKEvent sink (calendar)
```

The hub is the only process that talks to the relay. Passwords/Notes shells
read and write envelopes through the IPC store; the hub pushes/pulls.

## KMP entry points this shell will call (nothing else)

| Kotlin FQN | Used for |
|---|---|
| `core.data.RemoteTodo` | Envelope on `todos:<vaultId>`: `uid / title / notes / done / dueAt (epoch ms, nullable) / position / createdAt / deleted / updatedAt`. |
| `core.sync.adapters.TodoKind` | `syncId=uid`, LWW on `updatedAt`, canonical strips identity/position/timestamps (reorder is not an edit), `contentKey = "todo␀" + title + "␀" + createdAt`. Todos soft-delete. |
| `core.data.RemoteContact` | Envelope: name parts + organization + note + `phones/emails/addresses` (`TypedValue(value, type)`; type ints mirror Android `ContactsContract`) + ISO-8601 `birthday` + `etag/href/deleted/updatedAt`. |
| `core.data.RemoteEvent` | Envelope: `calendarId / summary / description / location / startMillis / endMillis / allDay / timezone / rrule (no RRULE: prefix) / recurrenceId / etag / href / deleted / updatedAt`. |
| `core.provider.SyncDriver` + `core.platform.RelayCursorStore` | Sync cycles; cursor keys `relay_cursor:<type>:<vault>:<channel>`. |
| `core.network.IosRelayTransport` | Ktor-Darwin + trust pinning (lands Phase 3; stub today). Channels via `recordChannel` (`passwords:`/`notes:`/`todos:` per vault), `members:`/`keys:`/`owner:`/`title:` per vault, `inbox:<deviceId>`, blobs `notes-blobs:<vaultId>`. |
| `core.network.RelayRoles` / `RelayLinks` | Role checks and `everysync://join/…` + share-link parsing stay in Kotlin. Passwords vaults offer EDITOR only, never VIEWER. |
| PQC cinterop (`IOS_PQC_NOTES.md`) | Same 10 fns as Passwords/Notes. |

## Todos → Reminders sink (no Todo app)

`EKEventStore` + `requestAccess(to: .reminder)`; synced tasks live in a
dedicated hub-managed `EKCalendar` (or the default list) and appear in the
system Reminders app. Field mapping:

| `RemoteTodo` | `EKReminder` |
|---|---|
| `uid` | `calendarItemIdentifier` via the hub's syncId↔identifier map (identifiers are hub-local state, not IPC columns) |
| `title` | `title` |
| `notes` | `notes` |
| `done` | `completed` (+ `completionDate`) |
| `dueAt` (epoch ms, nullable) | `dueDateComponents` (via `Calendar`; nil clears) |
| `position` | not mapped — sort client-side (excluded from the merge hash, so reorder is never an edit) |
| `createdAt` | read-only reference (part of the adoption content key; never written back) |
| `deleted=true` envelope | remove the `EKReminder` |

### Tombstone mapping

`EKReminder` has no tombstone: a user-deleted reminder simply vanishes from
the store. The hub therefore persists the set of known syncIds per vault;
after a healthy fetch, any known syncId with no matching `EKReminder` is a
genuine local delete and is folded into the next merge as a tombstone
envelope. Merge-driven deletes always arrive as tombstone envelopes, never
raw deletes. Same healthy-read discipline as `SyncSink.listLocalOk`: an
unhealthy fetch aborts the cycle instead of reading absence as deletion.

## Contacts sink

`CNContactStore` + `requestAccess(for: .contacts)`. Maps `RemoteContact`
name parts / organization / note / typed phones-emails-addresses / birthday
onto `CNMutableContact`; syncId↔`CNContact.identifier` lives in the hub map
(Swift never scribbles syncIds into user-visible contact fields). Delete and
conflict semantics follow the XCFramework entity kind — Swift holds no
opinion beyond faithfully applying `SyncDriver` output.

## Calendar sink

`EKEventStore` + calendar access. Maps `RemoteEvent` summary / description /
location / start-end millis / allDay / timezone / RRULE / recurrenceId onto
`EKEvent` in a hub-managed calendar. Same driver-applies, Swift-renders
split as above.

## Keychain / BGTaskScheduler

KMP `SecureStore` actual = Keychain `kSecClassGenericPassword` (shared
Keychain group with Passwords/Notes). `BGAppRefreshTaskRequest(identifier:
"com.vayunmathur.everysync.hub.refresh")` for periodic cycles plus a
`BGProcessingTaskRequest` for uploads after local system-store changes.
Scheduling only — cycles run in the XCFramework.

## Info.plist / entitlements notes

- `NSRemindersUsageDescription` — todos sink ("EverySync syncs shared task lists into Reminders").
- `NSCalendarsUsageDescription` — calendar sink.
- `NSContactsUsageDescription` — contacts sink.
- `com.apple.security.application-groups`: `group.<team>.everysync.shared` (envelope IPC store).
- `keychain-access-groups`: `$(AppIdentifierPrefix)com.vayunmathur.everysync.hub`.
- `UIBackgroundModes`: `fetch` + `processing` (BGTaskScheduler periodic work).

## Swift must NOT

Run merge/LWW, compute content hashes, encrypt envelopes, parse join links,
check roles, or re-implement TOTP/block parsing. Driver output in, system
stores out — nothing else.
