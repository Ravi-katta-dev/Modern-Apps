// EverySync Apple shells — blob sidecar store.
//
// Note image bytes keyed by `fileName` (see IPC_CONTRACT.md + `NoteKind`):
// envelopes carry only the name + `widthFraction`; bytes ride the blob
// channel `notes-blobs:<vaultId>` and land here. Zero sync logic — pure file
// I/O under the same App-Group container + `NSFileCoordinator` as
// `EnvelopeIPC.swift`.

import Foundation

public enum BlobStoreError: Error {
    case containerUnavailable
    case invalidFileName
    case io(Error)
}

/// Content-addressed-ish file store: `<group>/vault/<vaultId>/blobs/<fileName>`.
/// `fileName` values come from the XCFramework (`NoteKind.imageNames`); they
/// are `"<sha256>.jpg"`-shaped. This store REJECTS any name containing a path
/// separator or `..` so a hostile envelope can never escape the vault dir.
public final class BlobStore {
    private let fileManager = FileManager.default
    private let coordinator = NSFileCoordinator()
    private let queue = DispatchQueue(label: "com.vayunmathur.everysync.blob-store")

    public init() {}

    // MARK: - Ops (called only with XCFramework-derived names/hashes)

    /// Reads blob bytes; nil when absent (a missing blob is NOT an error —
    /// the hub prefetches via `NoteKind.imageNames(blocksJson)`; the note
    /// renders with a placeholder until the blob arrives).
    public func read(vaultId: String, fileName: String) throws -> Data? {
        let safe = try checkedFileName(fileName)
        return try queue.sync {
            let url = try blobURL(vaultId: vaultId, fileName: safe)
            var result: Data?
            var caught: Error?
            var coordError: NSError?
            coordinator.coordinate(readingItemAt: url, options: [], error: &coordError) { readURL in
                do {
                    result = try Data(contentsOf: readURL)
                } catch let error as NSError where error.domain == NSCocoaErrorDomain && error.code == NSFileReadNoSuchFileError {
                    result = nil
                } catch {
                    caught = error
                }
            }
            if let caught { throw BlobStoreError.io(caught) }
            return result
        }
    }

    /// Idempotent write keyed on `fileName`. Overwrites are safe: file names
    /// embed the content hash, so same-name bytes are same-content bytes.
    public func write(vaultId: String, fileName: String, data: Data) throws {
        let safe = try checkedFileName(fileName)
        try queue.sync {
            let url = try blobURL(vaultId: vaultId, fileName: safe)
            var caught: Error?
            var coordError: NSError?
            coordinator.coordinate(writingItemAt: url, options: [], error: &coordError) { writeURL in
                do {
                    try data.write(to: writeURL, options: .atomic)
                } catch {
                    caught = error
                }
            }
            if let caught { throw BlobStoreError.io(caught) }
        }
    }

    /// Lists blob file names present for a vault (for orphan GC on the hub).
    public func list(vaultId: String) throws -> [String] {
        try queue.sync {
            let dir = try blobsDirectory(vaultId: vaultId)
            var coordError: NSError?
            var names: [String] = []
            coordinator.coordinate(readingItemAt: dir, options: [], error: &coordError) { readDir in
                names = (try? fileManager.contentsOfDirectory(atPath: readDir.path)) ?? []
            }
            return names
        }
    }

    // MARK: - Paths

    func blobsDirectory(vaultId: String) throws -> URL {
        precondition(!vaultId.isEmpty && !vaultId.contains("/"), "vaultId must be a bare id")
        guard let container = fileManager.containerURL(forSecurityApplicationGroupIdentifier: EverysyncGroup.identifier) else {
            throw BlobStoreError.containerUnavailable
        }
        return container.appendingPathComponent("vault/\(vaultId)/blobs", isDirectory: true)
    }

    func blobURL(vaultId: String, fileName: String) throws -> URL {
        let dir = try blobsDirectory(vaultId: vaultId)
        try fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(fileName)
    }

    /// Rejects traversal + separators. Blob names are XCFramework-derived
    /// (`NoteKind.imageNames`), but the envelope is relay data — never trust it.
    func checkedFileName(_ raw: String) throws -> String {
        guard !raw.isEmpty,
              !raw.contains("/"),
              !raw.contains("\\"),
              raw != ".",
              raw != "..",
              !raw.contains("..") else {
            throw BlobStoreError.invalidFileName
        }
        return raw
    }
}
