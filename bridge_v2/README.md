# API101 bridge host start kit

This package is the concrete starting point for the **real** solution: a host module that is itself API101-compatible and loads the local API100 plugin inside it.

## What this kit contains
- a bridge runtime that loads `target-api100.apk` with `DexClassLoader`
- a reflective API100 interface proxy
- package-param callback proxies
- two host entry variants:
  - `Api101BridgeModuleNoArg.kt` for the observed no-arg API101 style
  - `Api101BridgeModuleCtor.kt` for older constructor-based modern-api lines
- scripts that extract plugin profile data and compare JNI symbol sets
- notes documenting the actual differences observed between the uploaded API100 and API101 APKs

## What is already prefilled for your current plugin
`app/src/main/assets/target-plugin-profile.json` already contains the metadata extracted from the uploaded local API100 plugin:
- entry class: `com.ss.android.ugc.awemes.ModuleMain`
- api range: `100 -> 100`
- target scopes: populated from `scope.list`

## How to continue from here
1. Open this project in Android Studio.
2. Choose **one** entry variant and keep only one class in `META-INF/xposed/java_init.list`.
3. Place the local API100 module at:
   - `/data/user/0/<bridge.package.name>/files/target-api100.apk`
4. Build a debug APK.
5. Install it and enable it in LSPosed for the same target scopes as the local plugin.
6. Watch LSPosed logs for the next missing bridge methods.

## Expected next round of fixes
The current bridge skeleton is intentionally focused on the first runtime hop. After the bridge host starts loading the API100 plugin, the next likely failures are:
- old hook callback shape vs API101 callback shape
- `invokeOrigin` compatibility
- `getClassLoader` vs `getDefaultClassLoader`
- `getApplicationInfo` vs `getModuleApplicationInfo`

## Important limitation
This bundle is a **source start kit**, not a finished APK. The current execution environment used to produce it did not include the Android dex signing/build chain, so the honest deliverable here is the bridge implementation package rather than a fake prebuilt APK.
