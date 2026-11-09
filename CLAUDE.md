# Hoard: notes for agents

SMB file client for Android (Capacitor 8 + React). Read `README.md` and `docs/adr/` first.

## Commands

- `bun run dev` (browser, web mock), `bun run lint`, `bun run typecheck`, `bun run test`, `bun run build`.
- `bun run android:debug` builds the APK (`android/app/build/outputs/apk/debug/app-debug.apk`);
  it sets `JAVA_HOME` to Android Studio's JBR (no system JDK on this Mac). Over SSH Apple/Android
  signing is not available; nothing is signed yet.
- Package manager is **bun**. Never commit secrets; credentials live in `skv`.

## Rules

- **UI follows the `hfps-app-ui` skill** (type scale, single card surface, density, list → card list
  below `@xl`, breadcrumbs via `Page` + `components/page/breadcrumbs.ts`, `ResponsiveDialog` for
  form dialogs (Drawer on phones), motion and gesture rules, no raw Tailwind colors). After any
  `shadcn add`: fix `from "cn"` imports, keep `sonner.tsx` without next-themes, re-apply the
  `tabs.tsx` focus fix and the `progress.tsx` `tone` variant.
- **No hard-coded UI strings.** Every string goes through i18next with keys in both
  `src/i18n/locales/en.json` and `pt-BR.json`.
- **Plugin contract rule.** `src/plugins/smb/definitions.ts` is the contract. Any change touches, in
  the same PR: `definitions.ts`, the web mock (`web.ts` + `mockData.ts`, with tests in
  `web.test.ts`) and the Kotlin plugin (`android/app/src/main/java/dev/hfps/hoard/smb/`, including
  `ErrorCode.kt`). Every rejection carries a `HoardSmbErrorCode` in `error.code`.
- **Bytes never cross the bridge**: transfers, open and thumbnails work with native files and URLs.
- **Passwords never go to SQLite or JS storage**: only `HoardSmb.setCredentials` (Android Keystore).
- **Schema changes** append a migration to `src/db/migrations.ts` (never edit a released one) and
  update `src/db/types.ts`, `docs/adr/0003-storage.md` and `src/db/db.test.ts`.
- All data access goes through the `Db` interface (`useDb`, `useLiveQuery`); the app's `Db` notifies
  live queries after every committed write (`src/db/changes.ts`).
- Code, identifiers, comments and ADRs in English.
