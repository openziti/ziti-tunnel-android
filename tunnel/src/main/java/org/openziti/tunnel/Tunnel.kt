package org.openziti.tunnel

class Tunnel {

    /**
     * A native method that is implemented by the 'tunnel' native library,
     * which is packaged with this application.
     */
    external fun tlsuvVersion(): String
    external fun zitiSdkVersion(): String
    external fun zitiTunnelVersion(): String

    companion object {
        // Used to load the 'tunnel' library on application startup.
        init {
            System.loadLibrary("tunnel")
        }
    }
}