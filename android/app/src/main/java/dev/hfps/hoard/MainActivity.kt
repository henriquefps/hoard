package dev.hfps.hoard

import android.os.Bundle
import com.getcapacitor.BridgeActivity
import dev.hfps.hoard.smb.HoardSmbPlugin

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Local plugins must be registered before super.onCreate creates the bridge.
        registerPlugin(HoardSmbPlugin::class.java)
        registerPlugin(SystemUiPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
