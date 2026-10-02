# Android 16／HyperOS 與 libxposed API 102 審核

目前狀態：程式遷移、arm64 builds、APK metadata／DEX、實機冷啟動及 modern module
識別已驗證。真正 captive portal 的 HTTP 登入及完整 Capture 流程仍待有測試網絡時驗證。

## 基準與驗收範圍

- 上游：`binarynoise/CaptivePortalAutoLogin`。
- Fork：`Night114514/CaptivePortalAutoLogin-`。
- 起點：`274-af8d47e-20260903`，commit `af8d47e17e389ea53a5ca3ebb6ee1d68b4a8c7da`。
- 保留 applicationId：`de.binarynoise.captiveportalautologin`。
- 實機：Xiaomi 14T Pro／`2407FPN8EG`、arm64-v8a、API 36。
- 已讀取的實機 fingerprint：`Xiaomi/rothko_global/rothko:16/BP2A.250605.031.A3/OS3.0.303.0.WNNMIXM:user/release-keys`。
- 實機 LSPosed Manager：`2.2.0-it`，versionCode `7906`；這個版本標示本身不證明 API 102 hooks 已成功執行。

工作分為兩個獨立 commits：先修正啟動常數讀取，再遷移 Xposed。
驗收需要 arm64 Debug／Release 建置、最終 APK metadata／DEX 檢查，以及
Xposed 停用／啟用兩條路徑的執行證據。沒有執行證據的項目保留為未驗證。

## Phase 0：既有程式審核

| Hook | tag 274 的行為 | Android 16／HyperOS 證據與遷移要求 |
| --- | --- | --- |
| `DoNotAutoOpenCaptivePortalHook` | Settings API 30 起、SystemUI API 35 起，在 `StandardWifiEntry.connect` 完成後設定 `mShouldAutoOpenCaptivePortal=false`。 | AOSP 有 `connect(ConnectCallback)`。實機 SystemUI 有單參數、雙參數 overload；Settings 另有三參數 overload。實機 DEX 顯示外層轉呼叫內層，最內層寫入旗標。不能沿用只按名字選第一個方法的做法。 |
| `HideForegroundServiceHook` | SystemUI API 33 起，在 `FgsManagerControllerImpl.UserPackage.updateUiControl()` 完成後，只對本 app 設定 `uiControl=UIControl.HIDE_ENTRY`。 | AOSP `FgsManagerController.kt` 及實機 DEX 都存在 nested classes、欄位與方法。 |
| `LocationIndicatorHook` | SystemUI API 31 起，過濾 `getActiveAppOps(boolean)` 回傳值中屬於本 app 的項目。 | AOSP 及實機存在該 overload 和 `AppOpItem.mPackageName`。既有行為過濾本 app 的所有 active AppOps，遷移不可暗中縮窄為只處理 location。 |
| `NetworkConnectedHook` | API 30 起，讓 `isVenueInfoNotificationEnabled()` 回傳 false；在 `updateNotifications(Network)` 前清除該 network 的 `mValidatedNotificationPending`。 | AOSP 及實機存在兩個方法、`mNetworkStatus` Hashtable 和 tracked status 欄位。兩個子功能應獨立安裝。 |
| `ReEnableSupportedNetworksHook` | API 31 起，對既有 portal liberators 的 `@SSID` 集合，讓 `shouldEnableNetwork(WifiConfiguration)` 提前回傳 true。 | 方法在實機 `service-wifi.jar` 中。舊程式卻在 NetworkStack 的 classloader 搜尋它，歸屬不正確；Android 16 AOSP 從 system_server 的獨立 Wi-Fi APEX classloader 載入。 |
| `SelfHook` | 把本 app 的 `Xposed.getEnabled()` 換成 true，並設定 legacy logging。 | 全專案搜尋只有宣告及 SelfHook 自己的引用，UI／Application／service 均無 consumer。可移除 SelfHook／Xposed object，毋須引入 framework service 依賴。 |

### Wi-Fi service 的程序與 classloader

Android 16 AOSP `SystemServer` 呼叫
`SystemServiceManager.startServiceFromJar("com.android.server.wifi.WifiService", ...service-wifi.jar)`。
`startServiceFromJar` 取得獨立 `PathClassLoader`、載入 service class，然後呼叫
`startService(Class)`，最後才建立 service instance。
實機 `/proc/1945/maps` 亦確認 system_server 映射
`/apex/com.android.wifi/javalib/service-wifi.jar` 及其 ART cache。

因此可在 `onSystemServerStarting` 解析 `SystemServiceManager.startService(Class)`，
僅匹配 `WifiService`，在 original call 前使用傳入的 `Class.classLoader`
註冊 `WifiBlocklistMonitor.shouldEnableNetwork`。不能假設初始 system_server classloader
已可找到 Wi-Fi APEX 的類別，也不應自行重建 loader 或監聽所有 class loading。

既有 SSID hook 對匹配 SSID 無條件回傳 true，可能越過原方法的永久停用判斷。
這是需明確記錄的既有行為；API 遷移不應順手改變它。

### Scope 審核結果

| 最終 scope | 實際用途 |
| --- | --- |
| `system` | Wi-Fi service 載入觀察及 `ReEnableSupportedNetworksHook`。API 102 的 system_server 虛擬套件名稱。 |
| `com.android.systemui` | FGS entry、AppOps、StandardWifiEntry hooks。 |
| `com.android.settings` | StandardWifiEntry hook。 |
| `com.android.networkstack` | AOSP NetworkStackNotifier hook。 |
| `com.google.android.networkstack` | 此 Xiaomi 實機安裝的 NetworkStack 套件；DEX 確認使用相同 `com.android.networkstack.NetworkStackNotifier`。 |

實機的 Google NetworkStack process 為 `com.android.networkstack.process`，
不能假定 process name 等於 package name。
舊 `android` alias 不能用來代替 modern `system` scope。
沒有現行 hook 支持保留 `com.android.providers.telephony` 或 `com.android.server.telecom`。

### 反射與啟動問題

`SharedPreferencesKt` 的靜態初始化原本列舉 `Settings.Global` hidden fields，
再用 `.single { it.name == fieldName }` 尋找三個 captive portal 常數。
HyperOS／Android 16 未回傳某個 Field 時，這個搜尋拋出 `NoSuchElementException`，
再包成 `ExceptionInInitializerError`，令 `Application.onCreate` 失敗。

全專案共有四個 hidden static value 呼叫點：

- `SharedPreferences.kt` 的 HTTP URL、HTTPS URL、user-agent key。
- `RecordCaptivePortalActivity.kt` 的 captive portal user-agent Intent extra。

Phase 1 把它們改成 private string constants，保留原有 fallback 表達式。
其後 static-field utility 只剩宣告，沒有 consumer；但 `HiddenApiBypass` 仍用於
`NetworkSuggestions`、舊平台的 signature public key 及 captive portal reevaluation，
因此不刪除整套 reflection utility 或 dependency。
`getHiddenInstanceField` 亦有 `.single`，且仍用於 Wi-Fi suggestion 欄位；這是另一個
vendor 相容性邊界，沒有在本次常數修補中改寫。

## 官方參考與 API 102 約束

- [LSPosed modern API 文件](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
- [API 102.0.0 artifact](https://central.sonatype.com/artifact/io.github.libxposed/api/102.0.0)
- [官方 API 102.0.0 sources](https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0-sources.jar)
- [libxposed/example](https://github.com/libxposed/example)：本次存取 repository 及 master／main 的 entry source 均回傳 404；未把非官方範例當成 API 定義。
- [AOSP Android 16 SystemServer](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/java/com/android/server/SystemServer.java)
- [AOSP Android 16 SystemServiceManager](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/core/java/com/android/server/SystemServiceManager.java)
- [AOSP Android 16 Wi-Fi blocklist](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/tags/android-16.0.0_r1/service/java/com/android/server/wifi/WifiBlocklistMonitor.java)

已直接讀取正式發佈的 source JAR，確認以下契約：

- entry 繼承 `XposedModule`，framework attach 後才在 lifecycle callbacks 初始化。
- package hooks 使用 `onPackageReady` 的 target classloader；system_server 使用專用 callback。
- `hook(Executable).setExceptionMode(PROTECTIVE).intercept(...)` 採用 interceptor chain。
- `module.prop` 使用 `exceptionMode=protective`、`autoHotReload=false`。
- Hook exception 在 proceed 前發生時，framework 繼續原 chain；proceed 後發生則保留先前結果。
  原方法本身的例外仍會傳播，已改動的 target 欄位亦不會自動回復。
- 不可以 catch original call 的例外後再次呼叫 `proceed()`。
- Optional target 缺少 class／method／field 時記錄診斷；避免 blanket catch 吞掉 framework error。
  官方 `HookFailedError` 文件明確要求不要把 framework bug 當作普通 fallback。
- API 102 禁止 legacy Xposed runtime calls；API dependency 必須為 `compileOnly`。
- Generic logger 應使用無 Xposed 型別的 injected sink，必要的 hook 診斷直接使用 modern logger。

## Phase 1 驗證紀錄

- `:app:assembleArm64Debug :app:testArm64DebugUnitTest`：成功；40 tests，0 failures／errors／skipped。
- `:app:assembleArm64Release :app:assembleX86_64Debug`：成功；release 保持 R8／resource shrinking 啟用。
- 編譯後 `SharedPreferencesKt` bytecode：三個 literal keys 直接傳入 `Settings.Global.getString`，沒有 hidden-field 查找。
- `git diff --check`：通過。
- 全專案 `test :app:lint`：在 `:api:client:test` 失敗；14 tests 中 11 failed、2 skipped。
  suppressed exceptions 指向 Windows 無法刪除仍被使用的 `db.sqlite`／`db.sqlite-journal`。
  該測試每次 setup 都刪除同一 temp directory，未先關閉上次建立的 server database。
  這個模組及測試沒有被 Phase 1 修改，也不依賴 app 模組；本次沒有加入無關的資料庫重構。
  此 run 未完成全部任務，不能視為全專案通過。
- app 的 arm64 Debug／Release、arm Debug／Release、universal Debug／Release、x86 Debug
  各有 40 tests 通過；這是同一組 JSON 測試跨 variants 執行，不能替代啟動／portal 測試。
- 獨立 `:app:lint`：exit 0，`BUILD SUCCESSFUL`；預設 universalDebug 報告有 13 warnings、0 errors。
  lint engine 另對既有 Kotlin 2.4 metadata 回報版本不相容診斷，因此分析覆蓋程度仍有限，
  不能把 task 成功解讀為完整 Kotlin 靜態分析沒有問題。
- 新建 Android 16 AVD 在安裝 app 前以 Windows `0xC0000005` 結束；沒有把這個 host crash
  當成 app crash，亦沒有把模擬器啟動嘗試當成 app 回歸通過。
- 實際 app 啟動、完整非 Xposed portal 功能及 API 102 framework 執行：尚未驗證。

以上是 Phase 1 與修改前審核紀錄，**不代表 API 102 遷移已完成**。

## Phase 2 實作

- 唯一 entry：`de.binarynoise.captiveportalautologin.xposed.CaptivePortalModule`。
- 正式 `io.github.libxposed:api:102.0.0` 在 app 使用 `compileOnly`；JVM tests
  使用 `testImplementation` 取得測試用 interface，不能視為 app runtime dependency。
- `META-INF/xposed/module.prop` 明確設定 min／target 102、static scope、停用 hot reload
  及 protective exception mode。每個 hook builder 亦明確使用 `PROTECTIVE`。
- legacy asset、四個 Manifest metadata、scope array、API 82 dependency／repository／
  catalog entry／ProGuard rules 均已移除；app 原有非 Xposed metadata 保留。
- `CaptivePortalModule` 只在 framework lifecycle 初始化；一般 Application／UI／service
  不引用它。沒有 libxposed 時，generic logger 的 nullable sink 維持未設定狀態。
- `HookContext` 集中處理 optional target discovery、包含程序與 loader 的診斷及成功後
  才記錄的同步去重。沒有反射呼叫 libxposed，沒有 `deoptimize()`。
- 每個 hook 安裝時解析並捕獲 Field／Method，避免在每次 callback 重新搜尋。
- Settings／SystemUI 的 connect 安裝只接受已核實的一、二、三參數 signatures；
  NetworkStack 的兩個功能分別安裝。
- `SelfHook` 及無 consumer 的 `Xposed.getEnabled()` 已移除，未新增自我 hook 或 service 依賴。
- Release 使用精確 entry keep rule；沿用上游 minification 設定。

### 已知行為修正與未改動的語意

Wi-Fi hook 從舊 NetworkStack loader 移到已核實的 system_server／Wi-Fi APEX loader，
修正原本找錯程序的問題。另加入此實機實際使用的 Google NetworkStack scope。
這兩項均有 AOSP／實機 DEX 證據支持。

SSID whitelist 仍來自既有 portal liberators 的 annotations；匹配時仍直接回傳 true，
因此原有越過 disable policy 的風險保留。其他 SSID 繼續 original method。
AppOps 仍過濾本 app 全部 active items；FGS 仍只隱藏本 app。

### 驗證工具與邊界

`tools/verify-modern-apk.py` 直接讀取 APK ZIP 及 DEX tables，檢查三個 metadata files、
五項 module properties、exact entry／superclass、arm64 native libraries、legacy DEX
字串引用及 libxposed API class definitions。合法的 compileOnly API references 可以存在；
API class definitions 不可以存在。工具亦輸出實際檔案的 SHA-256。

`HookContractTest` 使用正式 API interfaces 配合 fake chain，測試 original 呼叫次數、
例外傳播、connect overloads、AppOps／FGS package filtering、NetworkStack flag 修改順序，
以及 system_server Wi-Fi loader 的註冊時機、去重、缺少 target 和 SSID 行為。
測試 fixture 只存在 `src/test`，不會加入 APK。它們不能證明 ART hook 已安裝，亦不模擬
framework 的 protective exception handling。

獨立唯讀審查未發現已證實的高嚴重程度問題；其建議的 Wi-Fi loader 測試已補入。
所有五個 hooks 的 target signatures 都能在已擷取的實機 DEX 靜態核對；實際 callback
觸發、ART 最佳化影響及啟用後程序穩定性仍須實機測試。

### 實機驗收步驟

1. 安裝與 SHA-256 對應的 arm64 測試 APK。上游 164 的簽署與本機測試 key 不同；
   移除舊版前須保留需要的 app 設定／記錄，並明確同意資料清除。
2. 先在 LSPosed 停用本模組。冷啟動 app，檢查沒有 SharedPreferences
   `ExceptionInInitializerError`，完成權限 UI，啟動前景監察 service。
3. 在 Advanced 選擇 portal test backend 和 user-agent，重新開啟確認設定保留。
   測試手動 Liberate、Request Re-evaluation、Capture Captive Portal Login。
4. 在受控 captive portal Wi-Fi 驗證偵測、HTTP liberation、成功後連線狀態；
   有正常互聯網的 Wi-Fi 不能替代這個測試。
5. 在 LSPosed 確認顯示 modern module／API 102，啟用上表五個 scopes 後重啟。
   `autoHotReload=false`，不要假定更新 APK 即已重新安裝 system hooks。
6. 檢查 modern module load／Installed diagnostics，確認 system_server、SystemUI、
   Settings、實際 NetworkStack 程序保持正常；分別觸發對應功能。
7. 缺少 vendor target 的警告表示該功能沒有啟用；不能只憑沒有 crash 就宣稱所有 hooks 成功。

## 實機發現的額外 onboarding 修補

已安裝的 Debug 版在完成通知授權後，於 2026-10-02 04:56:11 出現
`LocationFragment not attached to a context`。完整 app log 顯示
`PermissionsFragment.onResume` 經舊 callback 存取已移除的 `LocationFragment`。
這與 SharedPreferences 常數問題不同，亦不是 modern hook 的 exception。

原程式把 `LocationFragment` 內嵌的 permissions container 加到 Activity 的
FragmentManager，令內嵌 fragment 可以比其畫面 parent 活得更久。
修補只限 `LocationFragment.kt`：使用 `childFragmentManager` 管理內嵌頁、
使用 `parentFragmentManager` 離開 onboarding、避免重複排程下一頁，並在
權限已齊全時不建立即將失去 parent 的內嵌頁。沒有 UI redesign。
[Android 官方 FragmentManager 文件](https://developer.android.com/guide/fragments/fragmentmanager)
亦區分 child 與 parent manager 的歸屬。

這項修補另作 commit `6065176`，避免混入 API 102 遷移。原定 Phase 1 constants
commit 是 `70ddcf6`。修補後 arm64 Debug／Release、50 個 app tests 及 lint task
通過；在 Release 實機重走通知權限流程、完成 onboarding、切背景及恢復，
沒有重現舊 callback crash。

## 目前已取得的 Phase 2 驗證證據

- arm64 Debug／Release 均成功建置，Release 保持 R8／resource shrink。
- `tools/verify-modern-apk.py` 對兩個實際 APK 均通過：三個 metadata、exact entry、
  arm64-only native libraries、零 legacy runtime DEX references、零 bundled API definitions。
- Debug 有 20 個 DEX／21,474 個 classes；Release 有 3 個 DEX／10,211 個 classes。
- `:app:testArm64DebugUnitTest`：40 個既有 JSON tests + 10 個 hook contract tests，
  全部通過，沒有 skipped。全專案 run 的 app 各 ABI／Debug／Release variants 亦通過。
- `test :app:lint --continue`：app tests／lint 完成，但整體因 `:api:client:test`
  的既有 Windows SQLite file lock 問題失敗（14 tests，11 failed，2 skipped）。
- 最新獨立的 builds／focused tests／lint run：`BUILD SUCCESSFUL`；lint XML 為
  13 warnings／0 errors。既有 lint Kotlin metadata 版本診斷仍限制其分析覆蓋。
- 實機 LSPosed `lspctl status` 明確回報 API 102；Manager 的此 app 卡片顯示
  `API 102`，並可讀取 modern description／static scope。
- 模組尚無 scopes 時，Debug 與 R8 Release 皆冷啟動成功，沒有已知 SharedPreferences
  `ExceptionInInitializerError`。Release service 的 dumpsys 顯示 `isForeground=true`。
- 一般 Wi-Fi 連線 callback、網絡狀態辨識、手動 Liberate 的 `no portal` 路徑、
  Request Re-evaluation 的 network report 已在 app 自身 log 核對。
- Backend 改成 Google、User Agent 改成 Chrome/Android，冷啟動後仍保存於 app preferences。
- 使用者確認目前沒有 captive portal 測試網絡。Release 的 Capture 入口依既有程式，
  只在 `networkState.hasPortal` 為 true 時啟用，因此目前一般 Wi-Fi 不能證明其
  完整 Gecko capture 或 HTTP liberation 已通過。這兩項保留為未驗證。

### 啟用及重啟後的 framework 證據

2026-10-02 15:16 的實機重啟後，`sys.boot_completed=1`。LSPosed 日誌記錄：

- `system` 載入 API 102 module，安裝 `SystemServiceManager.startService(Class)`。
- Wi-Fi service 啟動時安裝 `WifiBlocklistMonitor.shouldEnableNetwork(WifiConfiguration)`；
  loader 同時包含 `/apex/com.android.wifi/javalib/service-wifi.jar` 及 HyperOS
  `/system_ext/framework/miui-wifi-service.jar`。
- Google NetworkStack 的 `com.android.networkstack.process` 安裝兩個 notifier hooks。
- SystemUI 安裝 FGS／AppOps hooks 及兩個 StandardWifiEntry.connect overloads。
- Settings 安裝三個 connect overloads；其 `:remote` process 亦載入同一 package hooks。

當次觀察中 system_server、SystemUI、Settings、NetworkStack 均存活，crash buffer 沒有
這四個目標或本 app 的 crash process records；啟用後 app 冷啟動仍回報 `Status: ok`。
這證明安裝及短期啟動穩定，不能證明沒有長期問題，也不能替代每個 portal／SSID 情境。

原本的兩個獨立工作仍分開：`70ddcf6` 是 Android 16 常數修補，API 102 遷移另作 commit。
實機發現的 onboarding 問題額外用 `6065176` 隔離，故總提交數比原定兩個多一個。

### 建置環境與重現

Windows 使用本機 Temurin 17、Android SDK API 36／build-tools 35.0.0 及專案 Gradle wrapper。
曾遇到中斷後的本機 cache archive／lock 損壞；保留損壞 cache 後重建，沒有因此升級依賴。
下列 task 名稱可由其他環境的 `./gradlew` 執行；本機使用 `gradlew.bat`：

```text
:app:assembleArm64Debug :app:assembleArm64Release :app:testArm64DebugUnitTest :app:lint
test :app:lint --continue
```

本機建置另使用 `--no-build-cache --max-workers=2 --console=plain`、2 GiB Gradle heap，
以及 Windows socket／短 temp path 的 JVM 設定。這些屬本機執行參數，沒有加入 tracked
project configuration。Release 在沒有 production keystore configuration 時沿用專案
既有 debug signing fallback；不是上游簽署，不能直接覆蓋上游 APK。

最終檔案：`app/build/outputs/apk/arm64/debug/app-arm64-debug.apk` 及
`app/build/outputs/apk/arm64/release/app-arm64-release.apk`。每次重建後應重新執行：

```text
python tools/verify-modern-apk.py app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
python tools/verify-modern-apk.py app/build/outputs/apk/arm64/release/app-arm64-release.apk
```

工具輸出的 SHA-256 對應該次實際 APK；Git commit 改變會透過既有 version generation
改變 APK，因此不要把較早一次 build 的 hash 套用到之後的檔案。
