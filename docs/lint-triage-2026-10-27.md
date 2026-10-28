# Lint 分流與非實機修正（2026-10-27）

基線為 `436c6cc`：0 errors、50 warnings、3 hints。本輪未升級 SDK／依賴、
未修改簽署金鑰或套件名稱、未提交／推送／發布，也未操作使用者裝置。
以下為本機工作樹驗證；不取代實機驗收。

## 本輪處理

| 規則 | 原數量 | 修正與驗證範圍 |
| --- | ---: | --- |
| DataExtractionRules | 1 | 保留 allowBackup=false，增加 fullBackupContent=false 與 Android 12+ 雲端／裝置轉移全資料域排除；補 mutation tests 與 merged manifest CI gate |
| AppBundleLocaleChanges | 1 | 關閉 AAB language splitting，讓 app 內語言切換不依賴額外下載；Gradle DSL 建置與靜態防退步檢查，不代表 AAB 安裝驗收 |
| MissingQuantity | 12 | 西／法／義／葡三組 plurals 補 many，保留 other 及既有格式參數；補資源結構／參數測試 |

備份排除只是加強原有拒絕政策，不是憑證遷移，也不刪除使用者現有資料。
所有語系必須留在 AAB，預期增加下載體積；目前未改正式版本號、商店設定或部署。

## 本機結果

- 修正後 `lintDebug`：**0 errors、36 warnings、3 hints**，14 個警告已排除。
- `assembleDebug assembleDebugAndroidTest` 成功。
- 45 項 Python safeguards／資源測試通過；211 項 JVM／loopback SSH／SFTP 通過，零跳過。
- merged debug Manifest 檢查通過；以 aapt 讀取最終 APK，確認 allowBackup 與
  fullBackupContent 皆為 false，dataExtractionRules 指向已打包的規則；兩個 transport
  各有九個全路徑排除。這不是實際 backup transport 測試。
- 本次 CI gate 修改尚未推送，不能宣稱遠端已執行新增檢查。

## 保留而非隱藏的項目

| 規則 | 基線數量 | 判斷與後續 |
| --- | ---: | --- |
| ClickableViewAccessibility | 1 | 終端機有點按、拖曳選取、滑動等手勢。需設計實際可用的 performClick／無障礙動作及測試，不加空 override 消音 |
| PluralsCandidate | 15 | 需追到每個呼叫點確認計數語意、改 plural API 並更新十語系，不能只改資源型別 |
| ConfigurationScreenWidthHeight | 1 | 對話框高度需要改以實際容器計算，須兼顧鍵盤、分割畫面及不同視窗；留給 UI 專項驗證 |
| OldTargetApi | 1 | target 36 不等於 compile 37；提高 target 會改行為，未擅自升級 |
| NewerVersionAvailable | 3 | Kotlin／Compose plugin／JSch 更新另行評估與完整回歸，不為消警告順帶升級 |
| UnusedAttribute | 1 | localeConfig 在舊 API 被忽略；保留相容宣告，不改 min SDK 以消警告 |
| EmptySuperCall | 2 | 父類空方法呼叫屬低風險清理，不改連線／清理生命週期 |
| ObsoleteSdkInt | 2 | 既有版本判斷與圖示資料夾可再整理；此輪不調整圖示或 SDK 邊界 |
| UnusedResources | 7 | 清理前需檢查所有呼叫點及十語系；不把刪文案當作修正憑證功能 |
| MonochromeLauncherIcon | 2 | 需提供並校閱單色圖示，不用任意資產替換 |
| UseKtx | 1 | URI 寫法建議，與本輪安全／語系修正無關 |
| AutoboxingStateCreation（hint） | 3 | 可再改專用 Long state；目前未為純效能提示更動鎖屏狀態邏輯 |

沒有加入 Lint baseline、SuppressLint、忽略規則或提高錯誤門檻。
數量會隨工具／依賴版本與網路版本檢查變動，不能把這份基線當永久總數。

## 實機與後續邊界

仍需另驗證備份／OEM 裝置轉移、AAB 安裝後離線切換語言、plural 選擇與版面，
以及終端 TalkBack／D-pad／觸控手勢。本輪不操作平板，也不宣稱這些已通過。
網站 Android／iOS 安全文案更新是下一個獨立項目，此輪未修改或部署網站。
