package org.openziti.tunnel

class NativeLib {

    /**
     * A native method that is implemented by the 'tunnel' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        // Used to load the 'tunnel' library on application startup.
        init {
            System.loadLibrary("tunnel")
        }
    }
}