import type { CapacitorConfig } from "@capacitor/cli"

const config: CapacitorConfig = {
  appId: "dev.hfps.hoard",
  appName: "Hoard",
  webDir: "dist",
  android: {
    // Capacitor's debug logging prints every plugin call with its arguments to logcat, which would
    // include the password passed to HoardSmb.setCredentials. Keep bridge logging off.
    loggingBehavior: "none",
  },
  plugins: {
    CapacitorSQLite: {
      androidIsEncryption: false,
    },
  },
}

export default config
