# 非 debug 自用版收尾

此變更只為現有平板測試安裝準備長期自用建置，不加功能、不合併正式分支、
不部署網站、不發布商店版。使用者回報目前 debug 測試版正常；這不是新 personal
建置、R8 或所有裝置／安全情境已通過驗收的證明。

## 保留現有安裝身分

| 項目 | 現有 debug | 新 personal |
| --- | --- | --- |
| applicationId | `com.sshborg.debug` | `com.sshborg.debug` |
| 顯示名稱 | SSHBorgDebug | SSHBorgDebug（保留以識別既有安裝） |
| versionCode／versionName | 37／1.19.1 | 37／1.19.1（此次不增加） |
| 可除錯 | 是 | 否 |
| R8／資源縮減 | 否 | 是，沿用 release 規則 |
| 發布簽章 | 固定 tablet-test key | 同一固定 tablet-test key |
| 下載資料夾 | `Download/SSHBorg-debug/` | `Download/SSHBorg-debug/` |

`personal` 從 release 設定繼承，但明確清除 signingConfig；建置不會載入正式
簽署金鑰，也不會自動生成替代 key。只有既有隔離式 CI publisher 在完成測試後
使用固定 tablet-test Secrets 重簽。套件、版本、Room schema、Keystore aliases 與
認證規則不變；沒有啟動自動遷移、清資料或放寬 ON_STOP。

下載資料夾改由安裝套件決定，而不是 `BuildConfig.DEBUG`，以免關閉除錯後看不到
原有下載位置。除錯診斷與 Compose tooling 不啟用；設定頁也不再顯示「debug」標記。
正式 `com.sshborg` 的建置與資料夾不變。

同套件、同憑證與版本碼未降低是更新的必要條件，不是資料或 Keystore 可用性的保證。
不要為安裝而移除舊 app／清資料；設定匯出不包含私鑰與密碼，原始憑證應另行安全保管。
若更新失敗，保留原安裝並回報錯誤；不要關閉 Play Protect 或繞過 Android 簽章檢查。

## 本機與 CI

使用 JDK 21 與 repo 指定 SDK：

```sh
python3 scripts/check_security_policy.py
python3 -m unittest discover -s scripts -p 'test_*.py' -v
python3 scripts/run-local-security-tests.py
./gradlew --no-daemon lintDebug assembleDebug assembleDebugAndroidTest
./gradlew --no-daemon lintPersonal assemblePersonal
python3 scripts/check_personal_apk.py app/build/outputs/apk/personal/app-personal-unsigned.apk \
  --aapt2 "$ANDROID_HOME/build-tools/37.0.0/aapt2"
python3 scripts/check_tablet_signing.py --apksigner "$ANDROID_HOME/build-tools/37.0.0/apksigner"
python3 scripts/check_tablet_signing.py --apksigner "$ANDROID_HOME/build-tools/37.0.0/apksigner" \
  --variant personal --aapt2 "$ANDROID_HOME/build-tools/37.0.0/aapt2"
```

完整 fixture JVM suite 使用 debug 程式碼執行；即使通過也不能代替縮減後 APK 的實機測試。
簽章回歸只使用可拋棄 key，驗證兩批實際 APK、錯誤 pin／缺少設定拒絕、篡改拒絕、
暫存清理，以及真正 debug APK 不能冒充 personal；它不安裝 APK、不讀永久 Secrets。
personal 回歸也以同一把 fixture key 重簽 debug 對照組，實際驗證跨 variant 的憑證一致；
這仍不是舊發布版到新自用版的裝置更新測試。

獲得本次提交／推送與發布授權後，才能執行：

```sh
gh workflow run security-validation.yml --ref security/harden-credential-storage \
  -f publish_tablet_test=true -f tablet_build_variant=personal
```

CI 預設仍不發布；一般 push／PR 只建置、測試。`personal` 僅於手動選擇時發布單一
`app-personal.apk`、來源 `build-info.json` 與 `SHA256SUMS`，採用
`personal-test-<run>-<attempt>` tag，仍是 prerelease／非 latest，沒有測試 instrumentation APK。
在來源 commit、run、variant、所有測試、checksum、編譯後旗標、憑證或 GitHub 資產 digest
不符時拒絕發布，不覆蓋舊附件。只有完成 publisher 的公開 APK 才是可安裝交付物。
本文件或修改 workflow 本身不構成公開發布授權。

## 本機驗證紀錄（2026-10-28）

- `lintPersonal assemblePersonal` 成功，包含實際 R8／資源縮減；產物為未簽署 APK。
  首次離線建置因缺少 `compose-group-mapping:2.4.20` 快取失敗，透過保留 TLS 驗證的
  代理／憑證設定下載後重跑成功；未修改依賴版本或 repo 代理設定。
- 以 aapt2 檢查實際 APK：`com.sshborg.debug`、versionCode 37、minSdk 30、非 debuggable／
  testOnly、備份關閉、沒有 Compose tooling component。生成的 BuildConfig 為 `DEBUG=false`。
- 完整 fixture JVM／loopback SSH／SFTP suite：213 項，零失敗、錯誤、跳過；
  包含新增的套件下載資料夾測試，不是 R8 程式碼的執行驗證。
- `lintDebug assembleDebug assembleDebugAndroidTest` 成功；兩種 app lint 都是零錯誤，
  仍有既有 36 warnings／3 hints，未加入 baseline 或關閉檢查。
- 82 項 Python safeguards、13 項網站回歸、靜態安全政策、workflow YAML／預設值與權限
  檢查，以及 `git diff --check` 通過。
- 實際 debug／personal APK 的可拋棄簽章回歸通過，包含 debug 對照組到 personal 的同憑證
  驗證與篡改／錯誤 pin 拒絕；fixture keys 與重簽產物已由測試清理，未使用永久金鑰。
- 尚未提交、推送或部署此變更，未使用固定永久金鑰簽署／發布 personal APK；
  未安裝到裝置、未執行 personal 的保留資料更新、認證拒絕或 R8 實機驗收。

## 精簡自用驗收（新 personal APK 交付後）

- 不移除 app，直接更新；確認主機清單、設定與既有測試金鑰仍在。
- 指紋或 PIN 完成新認證後，既有金鑰能用於 SSH、SFTP 與重新連線。
- 取消認證，不新增金鑰、不建立新連線；認證中切背景也不得建立新連線。
- 下載、開啟小型測試檔，確認仍用原來的 `SSHBorg-debug` 資料夾。

跳板／TV／商店／網站可以不納入這次個人收尾，但不能因此將其標記為已測。
舊憑證遷移沒有個人資料可測時可暫緩；此處仍不宣稱產品層面的遷移驗收通過。
