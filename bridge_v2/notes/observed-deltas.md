# Observed deltas from the uploaded APKs

## Local API100 module
- `META-INF/xposed/module.prop`: `minApiVersion=100`, `targetApiVersion=100`
- Entry: `com.ss.android.ugc.awemes.ModuleMain`
- DEX strings indicate old-style accessors such as:
  - `getApplicationInfo`
  - `getClassLoader`
  - `invokeOrigin`
  - `onPackageLoaded`
- JNI exports observed in `libnative.so`:
  - `Java_com_ss_android_ugc_awemes_ModuleMain_a`
  - `Java_com_ss_android_ugc_awemes_ModuleMain_n`

## Working API101 module
- `META-INF/xposed/module.prop`: `minApiVersion=101`, `targetApiVersion=101`
- Entry: `com.ss.android.ugc.awemes.ModuleMain`
- DEX strings indicate new entry/runtime pieces such as:
  - `onModuleLoaded`
  - `getDefaultClassLoader`
  - `getModuleApplicationInfo`
  - `getInvoker`
- JNI exports observed in `libnative.so`:
  - `Java_com_ss_android_ugc_awemes_ModuleMain_a`
  - `Java_com_ss_android_ugc_awemes_ModuleMain_d`
  - `Java_com_ss_android_ugc_awemes_ModuleMain_dv`
  - `Java_com_ss_android_ugc_awemes_ModuleMain_e`
  - `Java_com_ss_android_ugc_awemes_ModuleMain_fdr`
  - `Java_com_ss_android_ugc_awemes_ModuleMain_i`
  - `Java_com_ss_android_ugc_awemes_ModuleMain_idc`
  - `Java_com_ss_android_ugc_awemes_ModuleMain_idcf`
  - `Java_com_ss_android_ugc_awemes_ModuleMain_n`
  - `Java_com_ss_android_ugc_awemes_ModuleMain_s`
  - `Java_com_ss_android_ugc_awemes_ModuleMain_vs`

## Resulting bridge strategy
- Do **not** try to move rules from the local build into the cloud-oriented API101 APK.
- Host a real API101 bridge module that:
  1. loads `target-api100.apk` with a dedicated `DexClassLoader`
  2. instantiates the API100 entry with `(XposedInterface, ModuleLoadedParam)` when available
  3. proxies API100 callbacks and params to the API101 host runtime
