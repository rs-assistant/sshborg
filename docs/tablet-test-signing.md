# 固定測試簽章與更新

狀態：CI 支援與本機驗證已實作。使用者於 2026-10-27 回報已完成金鑰設定與備份，
接續提交、推送並由手動發布流程驗證設定。代理程式列出 Actions Secrets／Variables
的 API 權限仍回覆 403，不讀取秘密值，也不自行產生或替換永久金鑰。
實際固定簽章交付結果以對應的 GitHub Actions run、release 與公開憑證指紋為準；
使用者回報已設定不等於 CI 已驗證或實機更新驗收通過。

## 相容性與資料邊界

- 此金鑰只簽署 `com.sshborg.debug` 的平板測試版與對應 device-test APK，不是正式商店金鑰。
- 已發布的 `tablet-test-39755214534-1` 與 `tablet-test-39831417145-1` 使用不同的
  臨時 CI 簽章。新的固定金鑰不能讓它們突然支援覆蓋更新，APK 也無法還原舊私有簽署金鑰。
- 保留平板上的舊 app 與資料；不要為了安裝而解除安裝、清除資料或改動 Android Keystore。
  備份匯出不含憑證，不能用主機設定備份取代私鑰／密碼的保存。
- 採用固定簽章後，只有同 package、同憑證且 versionCode 未降低的 APK 才符合
  Android 保留資料更新的必要條件；資料／Keystore 是否仍可用需實機驗證。
  本變更不宣稱已完成這項裝置驗收，也不自行增加版本號或修改 applicationId。

## 管理者的一次性設定

在可信任、能妥善備份的裝置上操作，使用 repo 外的私有目錄。不要使用公開線上金鑰產生器。
以 JDK keytool 建立**獨立測試用** PKCS12；密碼由 keytool 互動提示輸入，不寫入命令列：

```sh
keytool -genkeypair -storetype PKCS12 -keystore tablet-test.p12 \
  -alias sshborg-tablet-test -keyalg RSA -keysize 3072 -validity 3650 \
  -dname "CN=SSHBorg Tablet Test"
```

此流程使用相同的 store／key password。將 keystore 加密備份，密碼另行安全保存；
GitHub Secrets 無法讀回原值，不是唯一備份。金鑰遺失不能用新金鑰維持原簽章。
不要把 keystore、base64、密碼或正式簽署金鑰加入 repo、Actions artifacts 或 release。

在 [Actions repository secrets](https://github.com/rs-assistant/sshborg/settings/secrets/actions)
設定兩個 Secrets：

| 名稱 | 內容 |
| --- | --- |
| `TABLET_TEST_KEYSTORE_B64` | 完整 PKCS12 檔案的 base64；base64 不是加密 |
| `TABLET_TEST_STORE_PASSWORD` | 上述 store／key password |

有可信任的 GitHub CLI 可直接傳入，不需把 base64 顯示到螢幕或聊天：

```sh
base64 < tablet-test.p12 | gh secret set TABLET_TEST_KEYSTORE_B64 --repo rs-assistant/sshborg
gh secret set TABLET_TEST_STORE_PASSWORD --repo rs-assistant/sshborg
```

第二個指令會要求安全輸入秘密；不要把密碼放在命令列參數或 shell history。
使用 keytool 顯示憑證的公開 SHA-256 指紋：

```sh
keytool -list -v -keystore tablet-test.p12 -alias sshborg-tablet-test
```

在 [Actions repository variables](https://github.com/rs-assistant/sshborg/settings/variables/actions)
新增 `TABLET_TEST_CERT_SHA256`，內容為該公開憑證的 64 位十六進位 SHA-256
（可有冒號，不可使用 APK 檔案 hash）。這不是秘密，用於拒絕意外更換的 signer。
不要為了讓錯誤金鑰通過而隨意改掉 pin；更換固定金鑰會破壞後續更新相容性。

## CI 的隔離與拒絕規則

- 一般 push／PR 維持無秘密的 Gradle 建置、測試及臨時 debug 簽章；其 build artifact
  不是可延續更新的固定簽章測試版。不要拿它覆蓋已安裝的固定簽章版本。
- 只有指定測試分支的手動 `publish_tablet_test=true` 才進入獨立 publisher job。
  金鑰在全部 Gradle／測試完成之後才出現，不會提供給 PR、Gradle daemon 或建置 cache。
- publisher 先核對來源 commit／run、零失敗／零跳過與原附件 checksum，再在 runner
  私有暫存目錄解碼金鑰，以 owner-only 權限保存；兩個 APK 都重簽並核對憑證。
- 私有暫存目錄在成功或例外離開時清除；不是記憶體或儲存媒體安全抹除的保證。
  簽署失敗不輸出工具的 stdout／stderr 或秘密。CI 不產生替代金鑰，也不退回隨機簽章發布。
- `build-info.json` 僅新增公開 signer SHA-256，保持來源與 `device_acceptance: NOT EXECUTED`。
  發布前再次實際驗證 APK 簽章及 checksum；遠端附件 digest 驗證仍保留。
- 固定金鑰的第一版與第二版都發布後，需比較憑證並在專用裝置測試保留資料更新。
  Secrets 配置完成、本機重簽成功或 CI 成功都不等同這項實機驗收。

目前不修改或覆蓋既有 release；管理者完成 Secrets／Variable 設定及必要的資料保留決策後，
再提交、推送並執行獲授權的測試發布。

## 本機驗證紀錄

- 38 項 Python safeguards／signing tests 通過，零失敗、零跳過；靜態政策檢查通過。
- 使用 JDK keytool 產生兩天有效期的可拋棄測試金鑰，對兩批實際 app 與 device-test APK
  分別重簽；實際 apksigner 驗證兩批皆為同一憑證，並核對 bundle checksum。
- 測試重新封裝沿用既有 211 項通過的 JVM 報告，並未在本次重簽驗證中重跑 JVM suite。
  來源／run metadata 為本機 fixture；沒有連線到 GitHub 發布，也不是新的 CI 建置紀錄。
- 可拋棄金鑰與重簽 APK 均僅存在臨時測試目錄，驗證完成已清理；未使用永久或正式金鑰。
- 未執行 Android 安裝、保留資料更新或 Keystore 實機驗收。
