# 網站安全說明：本機草稿與驗證

## 工作範圍

延續「先做非實機工作」計畫，保留前一輪備份／語系／複數修正；本輪新增網站文案與
產生器／測試修改。沒有提交、推送、部署網站或發布 APK，也沒有操作實機或使用永久簽署金鑰。
文案是這個 Android 安全強化測試分支的草稿，不宣稱已是商店版或完成產品級安全驗收。

## 文案調整

- 十語系首頁與指南新增醒目的 Android 11+ 測試分支範圍與驗收限制。
- 移除「開啟加密開關才保護憑證」的 Android 舊說明，改為強制 Keystore 憑證保護。
- 明確區分 app lock／應用內 PIN 與系統密碼學操作授權；新 transport 與每個儲存憑證
  的授權不可互相取代，取消或背景切換拒絕尚未完成的操作。
- 說明舊加密憑證需在設定明確選擇遷移並獨立回讀驗證，失敗／取消保留原資料。
  遺失舊密鑰、明文或損壞記錄需重新匯入／輸入；拒絕讀取不等同清除舊資料庫頁。
- 配置匯出不含私鑰、密碼與 host-key pin；不建議以卸載／清資料解決簽章衝突。
- 將「設備硬體加密」徽章改為「受保護憑據」，註明硬體背書依裝置而定。
- iOS 獨立標示並連到原 iOS 專案，不把本 repo 的 Android 認證／遷移政策當成 iOS 功能。
  英文隱私政策只修改 Android 相關段落，原有 iOS 段落不改。
- 備份指南的 JSON 範例改為格式 8 與 jumpHostLabels，移除找不到指定金鑰就忽略、
  使用裝置本地跳板 row ID 的舊說明；註明匯入拒絕與主機位置改變需重新信任。
  TV 章節增加系統授權前提，不把應用內 PIN 當作憑證授權的替代品。
- 保留 SSH 公鑰安裝、Android 截圖設定與格式轉換的既有說明；沒有保留
  「應用內 PIN 可取代憑證授權」或「任何能讀磁碟就一定能解密」之類不準確推論。

## 產生器與防退步檢查

需 Node.js 18+，現有 Android／Python 測試前置條件保持不變。

```sh
node --test site/test/security.test.js
node site/build.js --pages index,docs
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check_security_policy.py
```

- 13 項網站測試通過：十語系結構／非英文回退、Android/iOS 分界、政策段落、隱私政策、
  備份格式 8 範例解析、placeholder 展開、產生器輸入驗證，以及 20 頁範圍更新不改動既有 changelog／sitemap。
- 部分建置不讀發行標籤、不產生不存在的發行紀錄，也不重寫 sitemap；完整無參數建置入口保留。
- 已在本機重新產生 20 頁，檢查沒有未展開的 placeholder；發行紀錄與 sitemap 沒有 diff。
- 已將網站測試加入本機 CI workflow 設定，尚未推送，不宣稱遠端已跑過新步驟。
- 測試是結構與文字防退步檢查，不是自然語言語意驗證，不能取代母語與瀏覽器人工校閱。
- 重新執行完整 JVM／loopback SSH／SFTP：211 項通過、零失敗／錯誤／跳過。
  Python safeguards 45 項通過；Lint 與兩份 APK 建置成功，仍是 0 errors／36 warnings／3 hints。

## 發布前仍需確認

十語系母語校閱、手機／桌面版面與無障礙、商店版本與測試分支的標示、Android 隱私段落
的正式發布審核，以及實機生命週期／PIN／Keystore／Room／保留資料更新驗收。
iOS 行為未在此 repo 重新審核，不能用本輪網站測試宣稱已通過。
剩餘 36 個 Lint warnings、3 個 hints 的分流見 `lint-triage-2026-10-27.md`，沒有隱藏或豁免。
