// EverySync Hub — contacts -> `CNContactStore` sink.
//
// `CNContactStore` + `requestAccess(for: .contacts)`. Maps `RemoteContact`
// name parts / organization / note / typed phones-emails-addresses / birthday
// onto `CNMutableContact`; syncId<->`CNContact.identifier` lives in the hub
// map (`SyncIdMap.swift`) — Swift never scribbles syncIds into user-visible
// contact fields. Delete and conflict semantics follow the XCFramework entity
// kind (`ContactKind`: soft-delete; canonical vCard strips identity/transport
// metadata) — Swift holds no opinion beyond faithfully applying `SyncDriver`
// output.
//
// Type mapping (shared `PhoneKind`/`EmailKind`/`PostalKind` ints from
// `core.format.VCard` — values mirror `ContactsContract`, passed through
// unchanged):
// - Phone: HOME=1 -> home, MOBILE=2 -> mobile, WORK=3 -> work, FAX_HOME=4 /
//   FAX_WORK=5 -> fax, OTHER=7 -> other.
// - Email: HOME=1 -> home, WORK=2 -> work, OTHER=3 -> other.
// - Postal: HOME=1 -> home, WORK=2 -> work, OTHER=3 -> other.
// - Birthday: ISO-8601 `yyyy-MM-dd` <-> `DateComponents(calendar, y/m/d)`.
//   A malformed birthday does NOT fail the contact — the field is skipped and
//   the rest applies (foreign/corrupt tolerance, same as `parseRemote` null).
//
// MAC-CHECK (needs xcodebuild on a Mac):
// - `CNContactStore.requestAccess(for:completionHandler:)` deprecation
//   status on the deployment target (iOS 18 `requestAccess(for:)` async
//   variant — confirm on the Mac).
// - `CNSaveRequest` batching + `contactStore.execute(_:)` error mapping to
//   healthy/unhealthy write reporting (a failed save must NOT advance cursors).

import Contacts
import EverysyncCore

/// Applies XCFramework driver output for one vault's contacts.
/// Driver output in, `CNContact` rows out — nothing else.
final class ContactSink {
    private let contactStore: CNContactStore
    private let syncIdMap: SyncIdMap

    init(contactStore: CNContactStore, syncIdMap: SyncIdMap) {
        self.contactStore = contactStore
        self.syncIdMap = syncIdMap
    }

    // TODO(Mac): implement against the generated XCFramework `RemoteContact` /
    // `TypedValue` types:
    //
    // func apply(vaultId: String, envelopes: [RemoteContact]) throws:
    //   - deleted == true -> delete the mapped CNContact (via CNSaveRequest
    //     delete after fetch-by-identifier); drop the syncId from the map.
    //   - else fetch-or-create CNMutableContact; set given/middle/family/
    //     prefix/suffix/organization/note; phones -> CNLabeledValue with
    //     CNLabelPhoneNumber* per the table above; emails/postal likewise;
    //     birthday yyyy-MM-dd -> DateComponents (skip field on parse failure).
    //   - batch in ONE CNSaveRequest; execute once. Failure = unhealthy write.
    //
    // func detectLocalDeletes(knownSyncIds: Set<String>) throws -> Set<String>:
    //   - HEALTHY fetch (keys: identifier only) first; throw on ANY failure
    //     (caller aborts — same healthy-read discipline as listLocalOk).
    //   - known minus fetched = genuine local deletes -> tombstone envelopes.

    /// Phone type int -> Contacts label. Mirrors `VCard` codes.
    static func phoneLabel(for type: Int) -> String {
        switch type {
        case 1: return CNLabelHome
        case 2: return CNLabelPhoneNumberMobile
        case 3: return CNLabelWork
        case 4, 5: return CNLabelPhoneNumberMainFax
        default: return CNLabelOther
        }
    }

    static func emailLabel(for type: Int) -> String {
        switch type {
        case 1: return CNLabelHome
        case 2: return CNLabelWork
        default: return CNLabelOther
        }
    }

    static func postalLabel(for type: Int) -> String {
        switch type {
        case 1: return CNLabelHome
        case 2: return CNLabelWork
        default: return CNLabelOther
        }
    }

    /// ISO-8601 `yyyy-MM-dd` -> DateComponents. Nil on malformed input
    /// (caller skips the field, never fails the contact).
    static func birthdayComponents(from iso: String?) -> DateComponents? {
        guard let iso, !iso.isEmpty else { return nil }
        let parts = iso.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        var components = DateComponents()
        components.calendar = Calendar(identifier: .gregorian)
        components.year = parts[0]
        components.month = parts[1]
        components.day = parts[2]
        return components
    }
}
