# 合併前檢查與平板驗收紀錄（2026-10-22）

結論：**空白 pin 問題已完成本機修正與回歸；仍不代表完成發布驗收**。
初次合併前檢查發現非 null 的空白 SSH 主機金鑰紀錄會退回首次信任流程。
使用者同意修正後，已加入永久回歸測試並通過完整 JVM／transport suite。
以下保留初次診斷與當時遠端狀態；後續本機修正結果見文末。
本機修正驗證階段未 commit／push、未合併或發布；先前發布的測試版
`tablet-test-39755214534-1` 不含本次修正。使用者隨後另行授權提交、推送與發布
新的測試版；實際交付結果以對應 commit、GitHub Actions run 與新 release 為準。

## 初次檢查對象與遠端狀態（修正前）

- PR：<https://github.com/rs-assistant/sshborg/pull/1>，open／draft，GitHub 回報可合併、無衝突。
- head：`7dbc575b58a6b27768e46cf582169bc19bd0d5fc`，分支 `security/harden-credential-storage`。
- base：`V1`，`e8d10e269b7c6aafe478c92741f62b7de0eb8054`；未變更。
- head 的 push CI `39752494545` 與 PR CI `39752503639` 均成功。
  這兩次自動流程的發布 job 按設計跳過，不是測試套件跳過。
- 使用者要求發布的新測試版：`tablet-test-39755214534-1`，來源為相同 head；
  手動 CI `39755214534` 建置與發布成功，release 為 prerelease、非 draft。
- 該 APK 公開 SHA-256：`e7a48cfb209265feaeda06173e3b34ddea5eb7a358c48399f2bea0a3562b15d1`。

## 使用者回報的實機驗收

環境：Samsung Galaxy Tab S10 Ultra／Android 16。以下為使用者在此對話中回報，
不是代理程式直接操作裝置或取得 ADB／Keystore／網路稽核日誌的結果。
依對話順序對應上述新版測試 APK，未由裝置端另行讀取 APK hash。

| 情境 | 使用者回報 | 範圍／限制 |
| --- | --- | --- |
| 取消建立 TEST 金鑰時的認證 | 沒有新增 | 通過手動案例 |
| 指紋認證後建立金鑰 | 成功 | 通過此裝置手動案例 |
| 認證時按 Home 再返回 | 需要重新認證 | 通過手動案例；不等於取得完整 lifecycle trace |
| 改選 PIN／螢幕鎖定 | 成功 | 通過此裝置手動案例；不能推論所有 Android 11+ 裝置均可用 |
| SSH 連線 | 成功 | 使用者回報；未另確認驗證方式及所有拒絕／失敗分支 |
| SFTP 連線 | 成功 | 使用者回報；未推論所有上傳／下載／取消案例已測 |
| 重連 | 正常 | 功能通過；未另逐次確認所有認證提示或動態網路事件 |
| 跳板 | 使用者不需要，選擇不測 | 未測，並非通過或移除功能 |
| 舊憑證遷移 | 使用者沒有舊資料 | 個人案例不適用；不能豁免其他既有使用者的遷移驗收 |

## P2：空白的已儲存 host-key pin 被當成首次信任（初次診斷）

修正前位置：`app/src/main/kotlin/com/sshborg/data/ssh/SshManager.kt:423`；
同類問題亦存在於跳板路徑的 `:331`。

程式以 `knownHostsEntry.isNullOrBlank()` 選擇 `StrictHostKeyChecking=ask`，
且空白時不載入 known_hosts。`null` 本來代表未建立信任，但非 null 的空白紀錄
應屬無效的已儲存 pin，依安全規格必須失敗，不能重新要求信任並接受其他主機金鑰。
當紀錄損壞成空白時，修正前會失去「已配置 pin 不可重新接受」的保護。
這不是指紋／PIN 認證被繞過，也不是沒有提示就接受主機；問題是應拒絕的紀錄進入了 TOFU 流程。

重現方式：只使用 disposable loopback SSH fixture 的臨時 JUnit probes，
將 target 或 jump host 的 `knownHostsEntry` 設為 `" "`，並在首次信任 callback 回傳接受。
兩個案例均得到 `connected=true, asked=true`，而預期是直接失敗且不進入首次信任。

- 基線：205 個既有 JVM／transport tests，零 failures／errors／skips。
- 加入兩個臨時診斷案例後：207 tests，2 failures，都是上述拒絕斷言；
  `targetBlankPinMustFailClosed`、`jumpBlankPinMustFailClosed` 均重現問題。
- 臨時來源與 Gradle init script 位於 `/tmp/sshborg-premerge-probe.HPpC2X/`，不在 repo。
  fixture 的金鑰、passphrase、server 及 SFTP root 由既有 runner 產生並清理，沒有正式帳密。
  診斷透過 Kotlin 的 `debugUnitTest` source set 注入，未改 app 或 repo 的測試／build scripts。
- 測完移除臨時 source-set 注入後重新跑既有完整 suite，以免把診斷案例的失敗混稱為 CI 原有失敗。

建議修正：區分「沒有 pin（null）」與「無效 pin（空白／損壞）」，
target、jump、SSH／SFTP 共用相同 fail-closed 規則，並將拒絕案例加入永久回歸測試。
初次檢查未實作這項修正，後續修正見文末。

## 其餘發布驗收邊界

- `CredentialKeyPolicyTest`、`CredentialUpdateTest`、`HostBackupRoomTest` 的 Android
  instrumented tests 尚未執行；JVM 注入測試不是實際 Room／Keystore 的驗收。
- 未完成 application-level 動態網路稽核、lockout／enrollment-change、其他支援裝置、
  Android TV D-pad、跳板逐跳認證與真實舊資料遷移等產品級驗收。
  個人不使用這些情境可以略過個人測試，但 PR 仍保留這些功能。
- 網站來源如 `site/src/i18n/en.js:251` 仍描述舊版可選的加密開關，與本分支強制保護政策不同；
  正式發布前需要更新與校閱，不能照舊內容宣稱已完成所有安全驗收。
- 正式版本號、正式簽署／minified 發布驗證及商店發布流程未執行。本次沒有此類授權。
- 已發布 APK 的 `build-info.json` 保持建置時的 `device_acceptance: NOT EXECUTED`；
  這是 CI 當時未執行裝置測試的紀錄，不應修改已發布附件來取代本份後續人工驗收紀錄。

## 初次檢查重新執行的驗證（修正前）

- `python3 scripts/check_security_policy.py`：通過。
- `python3 -m unittest discover -s scripts -p 'test_*.py' -v`：26 項通過。
- 配置既有 JDK 21／Android SDK／專用 venv 後執行
  `scripts/run-local-security-tests.py --offline`：既有 205 項通過，零 failures／errors／skips。
- `git diff origin/V1...HEAD --check`：通過。

## 後續本機修正與永久回歸（2026-10-22）

- SSH／SFTP 共用的 `createSession` 在建立任何 transport 前檢查 target 與全部跳板：
  非 null 的空字串、空格、tab／CR／LF 紀錄均拒絕，不詢問首次信任，也不接觸前段跳板。
- 只有 `null` 進入首次信任；其餘非空白紀錄仍載入 known_hosts 並使用
  `StrictHostKeyChecking=yes`，格式錯誤或不符的 pin 不可重新接受。
- SSH 與 SFTP 各新增三項永久測試，涵蓋空白 target、第一／後段跳板，以及
  非空白但格式錯誤的 target／jump pin。既有正常首次連線、pin 重連及變更拒絕測試保留。
- 先只加入永久測試：211 項執行，4 項空白 pin 測試失敗，確認測試可重現漏洞。
- 套用修正後重新執行 disposable loopback suite：211 項通過，零 failures／errors／skips。
- 靜態安全檢查通過；Python safeguards 26 項通過。
- `assembleDebug assembleDebugAndroidTest` 成功，主程式與裝置測試 APK 均可建置；
  未安裝或執行 Android instrumented tests。既有 Compose test deprecation warnings 保留。
- `git diff --check` 與本份未追蹤文件的 whitespace 檢查通過。
- 本機結果不等同遠端 CI 或 Android 實機執行；不因本次修正完成而自動合併或發布。
