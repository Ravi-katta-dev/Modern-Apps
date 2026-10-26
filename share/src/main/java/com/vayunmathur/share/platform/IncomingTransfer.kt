package com.vayunmathur.share.platform

import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareState
import kotlinx.coroutines.flow.StateFlow

/**
 * What the receive notifications need from an incoming transfer, whichever protocol carried it:
 * a Quick Share [com.vayunmathur.share.network.transport.Connection] or an AirDrop session.
 *
 * [sessionHandle] is the identity a notification action carries. Quick Share handles are
 * native pointers (positive); AirDrop handles are negative, so the two never collide.
 */
interface IncomingTransfer {
    val sessionHandle: Long
    val state: StateFlow<ShareState>
    val pendingFiles: StateFlow<List<PendingFile>>
    val receivedFiles: StateFlow<List<ReceivedFile>>
    val bytesReceived: StateFlow<Long>
    val expectedTotalBytes: StateFlow<Long>
    val error: StateFlow<String?>
    val displayName: String
}
