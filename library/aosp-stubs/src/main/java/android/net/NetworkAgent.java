package android.net;

import android.content.Context;
import android.os.Looper;

/**
 * Stub of the @SystemApi {@code android.net.NetworkAgent} (framework-connectivity). Only the
 * surface :share's AirDrop link uses: constructing, registering, marking connected and
 * unregistering a local-only network for the Mosey {@code mosey0} interface.
 */
public abstract class NetworkAgent {
    public NetworkAgent(Context context, Looper looper, String logTag, NetworkCapabilities nc,
            LinkProperties lp, NetworkScore score, NetworkAgentConfig config,
            NetworkProvider provider) {
        throw new RuntimeException("Stub!");
    }

    public Network register() {
        throw new RuntimeException("Stub!");
    }

    public void markConnected() {
        throw new RuntimeException("Stub!");
    }

    public void unregister() {
        throw new RuntimeException("Stub!");
    }

    public Network getNetwork() {
        throw new RuntimeException("Stub!");
    }

    public void onNetworkUnwanted() {
        throw new RuntimeException("Stub!");
    }
}
