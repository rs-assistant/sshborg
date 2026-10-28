# 非實機驗證與剩餘工作（2026-10-27）

本輪依使用者要求繼續非實機工作；未連線或安裝平板、未使用永久簽署金鑰、
未推送、合併或發布新版本。基底提交為 `b409ddd`，以下修改與驗證是本機工作樹結果。

## 本輪完成

| 項目 | 結果與範圍 |
| --- | --- |
| 完整 JVM／loopback SSH／SFTP | 重新執行 211 項，零 failures／errors／skips；僅使用可拋棄本機 fixture |
| Python safeguards | 42 項通過，零跳過；含新加的拒絕斷言防假通過、簽署 CLI 失敗不洩密與密碼狀態翻譯案例 |
| 靜態政策與翻譯資源檢查 | 通過；是原始碼 tripwires，不是完整安全證明 |
| debug app／device-test APK 建置 | `assembleDebug assembleDebugAndroidTest` 成功；沒有裝置執行 |
| Android Lint | 已修復 4 個 MissingTranslation errors，`lintDebug` 成功；仍有 50 warnings／3 hints，沒有抑制或 baseline |
| 真實 APK 重簽回歸 | 兩批 app／device-test APK 維持相同可拋棄憑證，checksum 與來源／測試 metadata 不變 |
| 真實拒絕案例 | 不符憑證 pin、缺少簽署設定均拒絕且不改原 bundle；兩份 APK 分別修改 ZIP entry 後簽章驗證失敗 |
| 暫存與附件邊界 | 成功／拒絕後檢查 signer 暫存目錄清理；不把私有金鑰或其他檔案留在 artifact bundle |

原先的臨時真實重簽檢查已整理為 `scripts/check_tablet_signing.py`。
CI workflow 已加入 Lint，以及在 APK 建置後、無 Secrets 的 build job 執行此腳本的步驟，
並補上移除該步驟時會失敗的 mutation test。**尚未提交或推送，故不能宣稱新步驟已在遠端 CI 通過。**
腳本只驗證重簽與拒絕行為，不是不同來源版本之間的 Android 覆蓋更新。

## Lint：依賴問題已排除，阻擋錯誤已修復

- `lintDebug --offline` 無法解析未快取的 `com.android.tools.lint:lint-gradle:32.4.1`，
  尚未開始原始碼 lint 分析，不是已發現 app lint 錯誤或 lint 通過。
- 正常連線模式初次重試仍有 DNS 錯誤；curl 可透過環境代理連線，但 Java 不會自動採用
  `HTTPS_PROXY`。明確把既有無帳密代理的 host／port 傳給 Gradle，並保留環境 CA trust store，
  成功下載工具並開始分析。沒有停用 TLS 或更換依賴版本，也未將代理設定寫入 repo。
- 實際分析找到 4 個阻擋錯誤：`host_password_keep_saved`、`host_password_remove_saved`、
  `host_password_removal_pending`、`host_load_failed` 缺少九語系翻譯。
  已補在各語系 `security.xml`，並把這些 key 納入所有十語系的必要資源檢查與 mutation test。
- 再次以已快取依賴執行 `lintDebug assembleDebug assembleDebugAndroidTest --offline` 成功。
  未加入 lint baseline、忽略翻譯檢查或停用錯誤。剩餘 50 warnings／3 hints 不代表已修復：
  包含 plural quantity、無障礙、target SDK、bundle locale、舊資源與 debug／Manifest 提醒，需另行分流。
- 報告：`app/build/reports/lint-results-debug.html` 與 `lint-results-debug.sarif`。
  翻譯內容已通過 XML／AAPT／Lint 與格式檢查，但不宣稱已完成人工母語／版面校閱。

## debug APK Manifest 靜態檢查

以 Android SDK `aapt dump xmltree` 讀取實際建置的 APK，而非只檢查原始 Manifest：

- 最低 API 30；`allowBackup=false`。
- SSH foreground service、Room service 與 AndroidX initialization provider 未 exported。
- MainActivity 是 exported 的 launcher；debug APK 亦包含 exported 的 Compose PreviewActivity
  與 AndroidX ComponentActivity，以及受 `android.permission.DUMP` 保護的 ProfileInstallReceiver。
- `debuggable=true` 是此 debug 產物的預期設定。上述 debug 工具不能當作正式版本也安全的證明，
  正式 minified release 必須另檢查最終 Manifest、元件輸入與 R8 後行為。
- 這只是靜態元件盤點，並未驗證所有 exported Activity 的動態授權，也不是 application-level 網路稽核。

## 仍待處理的非實機項目

- 逐項分流 Lint 的非阻擋 warnings／hints；不因本輪零 errors 就宣稱零警告。
- 網站 Android 安全文案仍描述舊版可選加密；正式發行前需更新現有十語系，
  明確區分本 Android 分支與未在此 repo 審核的 iOS 行為，不能把 Android 政策直接套用到 iOS。
  本輪未修改或部署網站，也未宣稱人工翻譯校閱完成。
- 正式 R8／版本號與正式簽署發行檢查另行安排；不拿測試金鑰簽正式 app。
- 對完整 app 的來源／元件輸入安全檢查、動態網路稽核仍不能由這些測試取代。

## 延後、不能以本輪代替的實機項目

固定簽章的兩版本保留資料更新、Keystore、Room instrumented tests、真實舊憑證遷移、
lockout／enrollment change、Activity lifecycle 與各支援裝置／TV 操作仍需獨立驗收。
先前 Galaxy Tab S10 Ultra／Android 16 的使用者手動回報保留為個別證據，
不能推論其他裝置或此次尚未安裝的版本都已通過。不得為安裝新版而要求刪除舊 app／資料。
