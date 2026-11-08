# x86-in-Android 可執行方案（決策用）

**結論（先讀這段）**  
建議做**獨立原生 App**（新 package、新 repo），**不要假桌面**、不要當 Winlator／Termux 殼。  
工程上採**兩層正交問題**分開做：  
1) **指令翻譯**（x86_64→ARM64：先 Box64）  
2) **API 相容**（之後才加 Wine 跑 Windows PE）  

**第一版（P0–P1）先證明**：本機選一個 **Linux x86_64 ELF** → 全螢幕畫到 App Surface、有觸控／簡單音訊。  
**第二版（P2）**才上 **Wine 跑選定 Windows 工具／輕量遊戲**，啟動方式仍是「捷徑直進全螢幕」，不是 Win2000 桌面。  
**P3** 再衝 ARM64EC＋FEXCore／DXVK／較好 GPU 路徑。  

理由：Boss 痛點是「像 Win2000」＝產品 UX；底層仍是業界同一套層蛋糕（Wine＋dynarec＋rootfs／bionic），但**殼與啟動模型從 0 自己做**，可比 fork Winlator 更乾淨、可控授權與品牌。

---

## 1. 目標範圍

### 做什麼（v1 產品定義）
| 項目 | 決定 |
|------|------|
| 形態 | 獨立 Android App（Kotlin UI + NDK 引擎），非 Wusper 內嵌 WebView tool |
| UX | **無假桌面**：檔案／捷徑列表 → 一鍵全螢幕進程式；結束回列表 |
| CPU | 優先 **x86_64**（Box64）；32-bit x86 後期（Box86／BOX32／WoW64） |
| 客體類型 | **P0–P1：Linux x86_64 ELF**；**P2：Windows PE（經 Wine）** |
| 輸入 | 觸控虛擬搖桿＋游標模式；外接鍵鼠（P1） |
| 音訊 | 基礎播放（P1）；低延遲／WASAPI 完整度（P2+） |
| GPU | P0 可軟渲／簡易 GL；P1+ Vulkan 路徑（裝置相關） |
| Root | **不要求 root**（與 Winlator／Mobox／GameNative 同一約束） |

### 不做什麼（刻意砍）
- 不做 Winlator 式「容器裡開完整 Windows 桌面」當預設體驗  
- 不做 Termux／Mobox 當產品殼（終端＋腳本 UX）  
- 不做 Steam／商店帳號整合（那是 GameNative／Pluvia 路線）  
- 不做反作弊線上大作保證（技術與政策雙殺）  
- 不做「官方宣稱可跑任意 x86 軟體」——只承諾**白名單／標籤相容矩陣**  
- 第一版不做 Java 版 MC 原生（選項 2 另案）

### 為何不是「一上來就 Windows」
跑 Windows ＝指令翻譯 **＋** Wine API **＋** rootfs／bionic 邊界 **＋** 圖形／音訊 IPC。  
Winlator 11 仍在疊 Box64／FEXCore、Turnip／Vortek、DXVK 等（[Winlator 11.0](https://github.com/brunodev85/winlator/releases/tag/v11.0.0)；[架構說明](https://deepwiki.com/brunodev85/winlator/2-system-architecture)）。  
先 ELF 可在數週內驗 dynarec＋Surface＋輸入，避免半年卡在 Wine 啟動黑屏。

---

## 2. 技術棧建議（與為何）

### 建議預設棧（分階段）

```
[Kotlin/Compose 殼]
    → 啟動引擎進程 / Foreground Service
[客體世界]
    P0–P1: 最小 glibc rootfs + Box64 + 目標 ELF
    P2:    + Wine prefix（無 explorer 桌面；直接 wine path\to\app.exe）
    P3:    ARM64EC Wine + FEXCore（或 WowBox64）只模擬遊戲／主程式
[顯示]
    客體 X11／WL 輸出 → 進程內 X／合成 → ANativeWindow / Surface
    圖形：VirGL／llvmpipe（保底）→ Turnip（Adreno）→ 必要時 Vortek 式 IPC
[音訊]
    Wine/Pulse 或 ALSA bridge → 進程內 Pulse → AAudio
[輸入]
    MotionEvent / 鍵鼠 → 映射為 X 事件或 Wine 輸入
```

### 翻譯層怎麼選
| 方案 | 現況 | 建議 |
|------|------|------|
| **Box64**（ptitSeb，**MIT**） | Android 生態最成熟；經典 Winlator 鏈為 `proot → box64 → wine`；wrap 宿主庫、體積較輕（[AOSP Internals §66.5.6](https://aospbooks.github.io/aosp-internal-book/66-windows-games/)） | **P0 首選** |
| **FEX / FEXCore** | 官方 FAQ：**完整 FEX-Emu 不以 Android 為目標**（syscall／檔案系統／GPU 直通皆受限）（[FEX FAQ](https://wiki.fex-emu.com/index.php/FAQ)）；但 **FEXCore 可作 Wine ARM64EC 模組**（GameNative 預設） | **P3 選配**，勿當 P0 主幹 |
| **qemu-user** | 相容廣、慢，當除錯保底 | 僅 fallback |
| **Box64 ANDROID=1 NDK 原生** | 可編，但官方說明偏「跑 Android x86_64 ELF」，不是完整 Linux x86_64 世界（[box64#64](https://github.com/ptitSeb/box64/issues/64)） | 與「Linux rootfs 客體」路線分開評估 |

### rootfs／連結
- **經典路**：Ubuntu 系 **glibc imagefs** 解到 app 私有目錄 + **PRoot**（無 root 的 chroot；ptrace 有代價）（§66.3）  
- **新路（P3）**：**Bionic-Wine／ARM64EC**，減少 PRoot、讓 Wine／驅動更原生（Winlator Bionic、GameNative 預設）（[Pipetto-crypto/winlator](https://github.com/Pipetto-crypto/winlator)；§66.3.6、§66.5.8）  
- **P0 建議**：先 **小 rootfs + PRoot + Box64**（工程已知、除錯資料多）；並行研究 bionic 路徑，不要一開始兩條都做滿。

### 渲染／音訊／輸入
- **渲染**：客體不要直 `dlopen` 系統 Vulkan（glibc↔bionic 不能同進程亂鏈）。實務是 **thunk／wrap** 或 **IPC 到 bionic 渲染進程**（Vortek：guest ICD → socket → native renderer → 裝置 Vulkan）（[Vortek 說明](https://leegao.github.io/winlator-internals/2025/06/01/Vortek1.html)；§66.7）  
- **DirectX**：P2+ 用 **DXVK／VKD3D** 當 Wine DLL override，不是自己寫 D3D  
- **音訊**：進程內 Pulse → **AAudio**（§66.8）；接受百 ms 級緩衝換穩定  
- **輸入**：自研疊加層（虛擬鍵／觸控板），**不要**綁死「桌面裡開開始功能表」

### 為何不直接 fork Winlator／Termux+Mobox 當產品
| 對象 | 問題 | 我們要什麼 |
|------|------|------------|
| **Winlator** | 預設容器＋類 Windows 桌面 UX（Boss 已厭）；GPL 系聚合／組件授權複雜；fork 會永遠追 upstream 組件地獄 | **自研殼＋啟動模型**；執行期可**參考／有限度 vendor 組件**（須逐包授權審查），產品不是「又一個 Winlator 皮膚」 |
| **Mobox** | 掛在 **Termux** 上，Box64+Wine+腳本選單（[olegos2/mobox](https://github.com/olegos2/mobox)） | 獨立 APK、一般用戶不開終端 |
| **GameNative** | Pluvia 商店＋Winlator runtime 嫁接；預設 FEXCore+ARM64EC，方向先進但目標是 Steam 遊戲商店（§66.2） | 我們要「通用 x86 啟動器」且品牌獨立；可當**架構教材**，不當產品 fork |

**一句話**：偷師層蛋糕，自己做遙控與外殼；不要繼承假桌面與商店定位。

---

## 3. 原生 App 架構草圖

```
com.wusper.x86（暫名）
├── app（Kotlin / Compose）
│   ├── 程式庫／檔案挑選、相容標籤、設定（Box64 preset、解析度）
│   ├── SessionActivity + SurfaceView（全螢幕客體畫面）
│   ├── InputOverlay
│   └── EngineForegroundService（保活、通知「執行中」）
├── engine（NDK / C++）
│   ├── rootfs 安裝／校驗／patchelf
│   ├── 啟動：proot|loader → box64 → (wine) → target
│   ├── xserver / 合成 → 餵 ANativeWindow
│   ├── audio bridge → AAudio
│   └── 日誌／崩潰上報（可關）
└── assets 或 on-demand
    ├── 最小 rootfs（.tzst）
    ├── box64、可選 wine／dxvk（版本化組件）
    └── 預設不塞滿 Wine＋整棵 Ubuntu（體積）
```

### APK 體積與權限（務實）
- **Base APK**：殼＋引擎＋Box64＋極小 rootfs 或安裝器 ≤ **150–250 MB** 目標  
- **首次執行下載**：完整 image／Wine／驅動 **500 MB–數 GB**（與 Winlator／GameNative 同形態；GameNative 甚至用 on-demand feature module）（§66.2.5）  
- **權限**：儲存／媒體讀取（選檔）、網路（下組件）、前景服務、通知；**不要**亂要無障礙／裝置管理裝神弄鬼  
- **ABI**：只做 **arm64-v8a**（Box64 需求）  
- **minSdk**：建議 26+（JIT W^X／現代音訊）；實測再定 28／29

### 進程模型
- UI 進程（bionic）≠ 客體 Wine／Box64 進程  
- 圖形／音訊盡量 **socket IPC** 跨 libc 邊界（業界已驗證模式）  
- JIT：匿名可執行頁、`mprotect` 寫⊕執行分離（§66.4.3）

---

## 4. 里程碑與驗收

### P0 — 翻譯層活著（約 3–6 週，視人力）
**目標**：獨立 App 安裝 → 匯入／內建一個 x86_64 Linux 測試 ELF → 全螢幕跑起來。  
**驗收**：
1. 無 root 裝置上，`box64` 跑通至少 2 個測試 ELF（例如靜態／動態連結各一）  
2. 畫面進 App Surface（可先色塊／簡單 GL／X 視窗），不是 adb 終端截圖  
3. 返回鍵結束客體進程，不殘留殭屍  
4. 日誌可開關，崩潰有 tombstone／logcat 抓得到  

### P1 — 可玩的「啟動器」殼（約 +4–8 週）
**目標**：像模擬器前端，不像遠古 Windows。  
**驗收**：
1. 使用者從 SAF／檔案選 ELF，加入程式庫  
2. 觸控：虛擬鍵或游標模式至少一種可用  
3. 音訊：測試程式有聲（AAudio 路徑）  
4. 解析度／方向鎖定；背景切換可暫停或明確「不保證」  
5. 組件下載與校驗（hash）；失敗可重試  

### P2 — Windows PE 最小可用（約 +2–4 月）
**目標**：無桌面，直接 `wine app.exe`。  
**驗收**：
1. 白名單 **≥3** 個 Windows 程式穩定（建議：輕量工具 ×2 ＋ 輕度 2D／舊遊戲 ×1）  
2. 獨立 Wine prefix／每軟體容器可刪可重建  
3. DX9 類可走 DXVK 或明確標「僅 OpenGL／軟渲」  
4. 相容矩陣頁（成功／部分／失敗＋裝置 GPU）  

### P3 — 效能與現代棧（持續）
**目標**：逼近 Winlator Bionic／GameNative 預設路徑，但仍保持自己的 UX。  
**驗收**：
1. 可選 ARM64EC Wine + FEXCore／WowBox64  
2. Adreno：Turnip 路徑達標；Mali：有保底（VirGL／受限 Vulkan）並標紅  
3. Box64／FEX preset（Performance／Stability）可調  
4. 側載組件更新不強制整包 APK  

**並行（可選，不擋主線）**：選項 2「Java MC on Android」另開研究 spike，**不共用**此 App 的第一里程碑。

---

## 5. 最大風險

| 風險 | 說明 | 緩解 |
|------|------|------|
| **授權** | Box64 MIT；Wine／Pulse **LGPL**；Mesa 多 MIT；**整叉 Winlator／GameNative → GPL-3 義務**與閉源 shim 爭議（§66.2.7） | 自研殼；組件清單＋THIRD_PARTY；能連結則動態連結；避免無審查 vendor 整樹 |
| **效能** | 全模擬 Wine 極慢；TSO／多線程痛；PRoot ptrace 稅 | P3 縮到「只模擬主程式」；P0 接受非遊戲幀率；preset |
| **GPU 碎片** | Adreno+Turnip 相對好；Mali／PowerVR 常缺 extension（Vortek 一類補丁存在就是為這） | 裝置分級；保底軟渲；相容標籤 |
| **Google Play** | 模擬器／下載巨型 payload／執行使用者二進位，政策與審核風險高 | **優先側載／官網 APK**；Play 若上架需法務＋政策預審，勿預設能上 |
| **反作弊／線上遊戲** | 核心級反／驅動指紋必炸 | 產品文案明確「單機／無反作弊」；不做繞過 |
| **Android 執行限制** | 資料目錄 exec、W^X、seccomp／缺 SysV IPC | 跟隨 Winlator 類：loader／redirect／ashmem shim；盯新 API |
| **體積與儲存** | rootfs+Wine 以 GB 計；學校／低端機不夠 | 按需下載；可刪容器；小檔「試用 rootfs」 |
| **維護地獄** | 組件版本矩陣（Box64×Wine×DXVK×驅動） | 釘版＋相容矩陣；一次只升一條鏈 |
| **期望管理** | 「任意 x86」做不到 | 白名單思維；公開失敗案例 |

---

## 6. 與 Wusper／`com.wusper` 的關係

**建議：獨立 repo + 獨立 applicationId（例如 `com.wusper.x86`），不要塞進現有 Wusper APK／不要掛成 `/tools` Web。**

| 做法 | 評價 |
|------|------|
| 獨立 App | ✅ 體積、權限、Play／側載策略、崩潰隔離都正確；品牌可叫「Wusper X86」或新名 |
| wusper.com 導流 | ✅ 官網／社群放介紹＋APK；**不是**網站內跑 x86 |
| 塞進 `com.wusper` | ❌ APK 暴肥、審核／權限汙染、與現有 tools 生命週期綁死 |
| `/tools/...` Web | ❌ 瀏覽器跑不了這套 NDK／rootfs 棧 |

現有 Wusper Android 殼若只做「打開商店頁／下載獨立 APK」的深連結，可以；執行引擎必須分進程／分包。

---

## 決策請 Boss 拍板的三件事

1. **P0 是否同意「先 Linux ELF、P2 再 Windows」？**（強烈建議同意；否則時程×2 且早期無畫面可秀）  
2. **分發：官網側載為主，還是一定要進 Play？**（影響權限話術與是否塞大型 payload）  
3. **第一批 Windows 白名單想要哪 3 個程式？**（決定 Wine／DX 路徑優先級；不要先喊 3A）

---

## 參考（具體）
- Winlator 系統架構／Box64／組件：https://deepwiki.com/brunodev85/winlator/2-system-architecture  
- Winlator 11.0（Box64 0.4.0、Turnip、Vortek 等）：https://github.com/brunodev85/winlator/releases/tag/v11.0.0  
- 完整層蛋糕（GameNative／PRoot／ARM64EC／Vortek／音訊）：https://aospbooks.github.io/aosp-internal-book/66-windows-games/  
- Vortek IPC Vulkan：https://leegao.github.io/winlator-internals/2025/06/01/Vortek1.html  
- Mobox（Termux+Box64+Wine）：https://github.com/olegos2/mobox  
- FEX 官方「不做 Android 完整模擬」：https://wiki.fex-emu.com/index.php/FAQ  
- Box64 授權 MIT：https://github.com/ptitSeb/box64/blob/main/LICENSE  
- Winlator Bionic：https://github.com/Pipetto-crypto/winlator

---

## Boss 拍板（2026-11-08 ~23:34 HKT）

- 體積不擔心；分發不卡（官網／側載皆可，不強求 Play）。
- 啟動 UX：進 App → 單一界面列出／選擇裝置上的 `.exe`，或 App 私有工作區（開過的檔／專屬儲存）→ 直開全螢幕。
- 無假桌面；產品主路徑朝 Windows exe；Box64／Linux ELF 僅工程驗收層。

---

## Boss 拍板（2026-11-08 ~23:45+ HKT）

- UI 走**原生 Material 3**（Compose）。
- 下一步接引擎：假啟動 → 真啟動管線；P0 先 host ELF 驗收，Box64 可插位。

### 進度（2026-11-09 ~00:00 HKT）

- Box64 v0.4.4 已以 NDK android-28 編過並打進 APK assets；minSdk 升 28。
- P0 驗收主路徑：Android x86_64 靜態 ELF via Box64。Linux glibc ELF／rootfs／Wine 仍未完成。

### Boss 效能硬指標（2026-11-09）

- 同機同負載：Box64 ≥ 原生 arm64 **70%**（CPU 微基準；見 docs/ENGINE.md）。

### 進度（2026-11-09 ~00:15 HKT）

- Boss：要能跑真實 x86 應用（例 Codex），非 hello。
- 已接：選 exe→工作區→Wine／PRoot 鏈；組件首次下載；GUI／X11 與 Codex 本體仍待真機驗證。

### 進度（2026-11-09 ~00:25 HKT）

- 圖形：Xvfb -fbdir（XWD）→ SurfaceFrameBridge → Session Surface（最短路徑）。
- X11 overlay 發布於 Release `runtime-v1`。

### 進度（2026-11-09 ~00:30 HKT）

- 輸入：Surface 觸控→1280×720→xdotool（cmd 佇列）；目標 Codex 可點。
