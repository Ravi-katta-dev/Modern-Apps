package android.net;

import android.content.Context;
import android.os.Looper;

/**
 * Stub of the @SystemApi {@code android.net.NetworkProvider}. Registered through
 * {@code ConnectivityManager.registerNetworkProvider}, which callers reach reflectively so the
 * public {@code ConnectivityManager} is not shadowed here.
 */
public class NetworkProvider {
    public NetworkProvider(Context context, Looper looper, String name) {
        throw new RuntimeException("Stub!");
    }
}
