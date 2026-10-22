# 平板測試期間的安全複查（2026-10-22）

## 範圍與版本

本輪檢查 connection authorization、prompt cancellation／foreground invalidation、
terminal／SFTP 的 retry、stored credentials、jump chain 與 host-key pin 路徑。
這是針對性原始碼複查與本機回歸測試，不是完整安全稽核或裝置驗收。

基底 commit：`ee6fbcf173227d8dcfad0d3d59b91f9b41b690c8`。
第一輪修正已完成本機驗證，接著依使用者要求分階段 commit／push 至測試分支；
提交與遠端 CI 狀態以 git／Actions 為準。已發布供平板測試的 APK 仍是基底 commit，
不包含以下修正。請分別記錄兩個版本的測試結果。

## 發現與修正

### Simple jump chain 不應忽略無效項目

`parseJumpHosts` 原先會略過空 hop，因此 `,` 成為空鏈（direct route），
`first,,last` 成為較短鏈；無效 port 也會被當成 hostname 的一部分並改用 port 22。
新增的第一輪 7 個 parser tests 在舊實作有 5 個失敗，先確認重現才修改實作。

修正後拒絕整條無效鏈，驗證 username、host 與明確 port；IPv6 採 `[address]:port`
語法，傳給 JSch 時去除 brackets。合法鏈保留原本的順序與認證方式。
known_hosts 欄位改以 whitespace 分隔，避免 tab 讓已儲存的 pin 無法配對。

host editor 在儲存／加密新密碼前驗證 simple chain；SSH 與 SFTP 在要求認證前解析，
將解析錯誤顯示為 chain error，不啟動 transport。既有無效紀錄不會自動重寫；
使用者需在 editor 明確修正或清空鏈。

新增 8 個 parser tests 與實際 loopback SSH 的 simple-chain pinned reconnect test。
後者驗證保留 tab 分隔的 pin 後，可重連且不會重新要求首次信任。
IPv6 在本輪只驗證解析，未執行 IPv6 網路連線測試。

### Transport cancellation 不應成為一般錯誤

兩個 ViewModel 的 transport `runCatching` 原先也捕捉 `CancellationException`，
可能繼續寫入一般 error／password-prompt 狀態。現在在處理結果前立即重新拋出取消。

新增 loopback SSH 測試：等待首次 host-key 確認時取消，確認收到原本的 cancellation，
之後新的 attempt 可成功連線。這驗證 transport 的取消傳遞，不代表已直接執行 Android
ViewModel、Compose 或系統 prompt 的裝置流程。

## 驗證與仍需實機確認的事項

本機結果：189 個 JVM／transport tests 通過，零 failures／errors／skips；
10 個發布防護 tests 通過；device-test Kotlin 編譯通過。
取消測試曾因 coroutine stack-trace recovery 複製 exception 而失敗，已修正為檢查原本的
cancellation 是否保留在 cause chain，而不是要求同一個 exception instance；未放寬產品授權規則。

本輪驗證指令：

```sh
python scripts/run-local-security-tests.py --offline
python3 -m unittest discover -s scripts -p 'test_tablet_release.py' -v
./gradlew --offline --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process compileDebugAndroidTestKotlin
git diff --check
```

第一個指令使用已安裝 `scripts/requirements-security-tests.txt` 的專用 venv，
並配置 JDK 21、Android SDK 及可寫的 Gradle／Android user directories；詳見 `AGENTS.md`。
device-test 編譯不是 device-test 執行；本輪不重新打包或發布測試 APK。

原有 prompt／grant tests 涵蓋取消、晚到 callback、Activity 替換、背景後再返回、
錯誤 CryptoObject 及一次性 grant 的競爭消耗。兩個連線路徑的 password retry 仍重新
取得 grant；已建立 session 的恢復不是建立新 transport。這些檢查不證明 Android
硬體／PIN 成功，也不取代 application-level dynamic network audit。

嚴格的 `ON_STOP` 拒絕規則未變。某些裝置進入系統 PIN 畫面就會停止 host Activity，
可能因此連正確 PIN 也被拒絕；仍需實機證據，不可用放寬規則來掩蓋。
本輪新增的 editor 錯誤文案使用預設英文，完整語系翻譯及 TV D-pad 驗收仍待完成。

## 不需電腦／SSH server 的平板清單

只使用 `SSHBorgDebug` 與可拋棄的 `TEST` 金鑰，不輸入真實伺服器密碼或私鑰。

1. 「金鑰 → 產生」，名稱 `TEST`、Ed25519；取消系統認證。預期沒有新增金鑰。
2. 重試並完成系統 PIN／指紋。記錄成功與否、錯誤文字、使用哪種認證方式，勿記錄 PIN。
3. 認證畫面出現時按 Home，再回到 app。預期舊操作不會自行成功；重試需重新認證。
4. 若可行，在認證期間旋轉平板；確認舊 callback 不會讓操作重複完成。
5. 金鑰建立成功後，查看清單／改名。這些 metadata 操作不應額外解密私鑰；
   如有 app lock，那是獨立的 UI 鎖定流程。
6. 回報平板型號、Android 版本、APK 來源／版本及上述各步結果；錯誤截圖須避開秘密。

本輪 simple-chain 儲存拒絕與連線錯誤處理需使用之後包含修正的 APK 才能驗證，
不可用目前已發布 APK 的結果當作本輪修正的驗收。
SSH／SFTP retry、每跳認證、host-key 改變與 migration 的實機案例，等有專用測試 server／
可拋棄舊版資料時再做；沒有條件就記錄未測，不修改正式主機或使用者資料來湊測試。
