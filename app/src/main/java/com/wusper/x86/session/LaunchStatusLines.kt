package com.wusper.x86.session

/**
 * 0.1.106 UX：啟動狀態列動態人話庫（≥100 句繁中）。
 * **真階段綁定**選句 — 禁止同階段內 tick 隨機輪換（Boss 105 差評）。
 * 庫保留 ≥100；選句依 log 指紋／階段，可附短 metric。
 */
object LaunchStatusLines {

    enum class Phase {
        INSTALL,
        GPU,
        X_SERVER,
        WINEBOOT,
        RPCSS,
        DESKTOP,
        APP_LOAD,
        RUNNING,
        WAIT_PRESENT,
        FAIL_HINT,
        GENERIC,
    }

    private val pools: Map<Phase, List<String>> = mapOf(
        Phase.INSTALL to listOf(
            "正在準備運行時組件…",
            "核對根檔系統完整性…",
            "展開 Wine 前綴骨架…",
            "檢查映像層是否齊全…",
            "同步引擎資產到工作區…",
            "確認最小系統庫已就位…",
            "整理下載快取與校驗碼…",
            "安裝步驟進行中，請稍候…",
        ),
        Phase.GPU to listOf(
            "正在就緒 GPU 棧…",
            "探測 Turnip／Vulkan ICD…",
            "核對 DXVK 原生 DLL…",
            "檢查 KGSL 節點權限…",
            "配置顯示後端為 GPU…",
            "暖機圖形驅動，先別急…",
            "確認 ICD 路徑可用…",
            "圖形棧自檢中…",
        ),
        Phase.X_SERVER to listOf(
            "啟動進程內 X 伺服器…",
            "建立虛擬桌面 1280×720…",
            "掛上 Present／AHB 通道…",
            "輸入橋接準備中…",
            "音訊插座對接中…",
            "桌面繪製表面對齊中…",
            "X11 擴充模組載入中…",
            "顯示管線暖機…",
        ),
        Phase.WINEBOOT to listOf(
            "正在初始化 Wine…",
            "寫入 system.reg 骨架…",
            "第一次開瓶，稍等一下…",
            "登錄表還在長出來…",
            "Wine 前綴正在甦醒…",
            "系統 DLL 對位中…",
            "服務主機即將就位…",
            "開機腳本跑一半了…",
            "前綴還在熱身，別關畫面…",
            "Wineboot 心跳還在跳…",
            "正在補齊 Windows 假環境…",
            "登錄蜂巢（hive）寫入中…",
        ),
        Phase.RPCSS to listOf(
            "正在初始化 RpcSs…",
            "COM／RPC 服務點名中…",
            "等 RpcSs 亮綠燈…",
            "服務控制管理員握手中…",
            "遠端程序呼叫層暖機…",
            "RpcSs 快好了，再一下…",
            "系統服務佇列清點中…",
            "OLE／RPC 通道打通中…",
            "RpcSs 就緒，即將啟動…",
            "服務狀態查詢中…",
        ),
        Phase.DESKTOP to listOf(
            "虛擬桌面映射中…",
            "等 explorer 桌面就緒…",
            "桌面視窗類別註冊中…",
            "MAP_WAIT：等桌面標記…",
            "桌面殼層（shell）就位中…",
            "顯示桌面句柄對齊中…",
            "藍底桌面先鋪好…",
            "桌面座標與 Present 對齊…",
        ),
        Phase.APP_LOAD to listOf(
            "正在拉起客體 App…",
            "WoW64 路徑決策中…",
            "嘗試 /machine x86 啟動…",
            "建立 32 位元行程屬性…",
            "種子檔對位 C:\\App…",
            "啟動階梯換檔中…",
            "等 Loaded 指紋出現…",
            "CreateProcess 握手中…",
            "檢查映像機型 0x014c…",
            "備援路徑待命中…",
            "App 行程票根開立中…",
            "模組載入清單觀察中…",
            "路徑衛生檢查進行中…",
            "Wow64cpu 初始化探測…",
            "等 started pid 回報…",
        ),
        Phase.RUNNING to listOf(
            "客體行程在跑…",
            "心跳還在，畫面稍候…",
            "執行中，盯著 Present…",
            "行程活著，等第一幀…",
            "背景載入還在忙…",
            "客體沒掛，再給一點時間…",
            "執行脈衝正常…",
            "進程仍在，桌面待亮…",
        ),
        Phase.WAIT_PRESENT to listOf(
            "等待客體畫面（Present）…",
            "桌面在，App 視窗還在路上…",
            "第一幀還沒來：（",
            "Present 通道已開，等內容…",
            "非黑像素計數還在等…",
            "畫面合成器在聽…",
            "客體 HWND 搜尋中…",
            "顯示表面已就緒，等繪製…",
        ),
        Phase.FAIL_HINT to listOf(
            "啟動可能卡住，正在辨認原因…",
            "若一直空白，稍後看藍屏說明…",
            "失敗指紋蒐集中（僅本機 log）…",
            "階梯可能用盡，檢查 Loaded…",
            "建立失敗線索對位中…",
        ),
        Phase.GENERIC to listOf(
            "工程師也在螢幕後面幫你看著…",
            "再一杯咖啡的時間就好…",
            "複雜的事正在簡單地發生…",
            "管子都接上了，水還沒流出來…",
            "齒輪咬合中，請保持螢幕亮著…",
            "這不是當機，是在醞釀…",
            "進度在心跳裡，不在百分比…",
            "小步驟很多，畫面先陪你等…",
            "相容層正在學這支程式的脾氣…",
            "把舊世界的程式請到新手機上…",
            "耐心是啟動流程的隱藏依賴…",
            "我們寧可慢一點也要穩一點…",
            "底層還在打招呼，上層稍候…",
            "像在等公車：班次已出站…",
            "訊號在隧道裡，快出洞了…",
            "組裝最後一枚螺絲…",
            "檢查清單還剩幾格…",
            "暖機完成度慢慢爬升…",
            "背景工作比看起來多…",
            "別滑走，好戲在後頭…",
            "系統正在把舞台燈打開…",
            "幕後人員就位中…",
            "對白還在對稿，演員稍候…",
            "倒數不是數字，是氣味…",
            "再一下子，桌面會說話…",
            "把吵鬧的 log 煮成安靜畫面…",
            "相容魔法施展中…",
            "舊 EXE 正在學新舞步…",
            "橋還在鋪，車先別急…",
            "我們站在你這邊等結果…",
        ),
    )

    init {
        val n = pools.values.sumOf { it.size }
        require(n >= 100) { "LaunchStatusLines size $n < 100" }
    }

    fun detectPhase(logText: String, statusHint: String): Phase {
        val t = logText
        val s = statusHint
        when {
            s.startsWith("安裝") || "ComponentInstaller" in t && "WINEBOOT_INIT" !in t && "WINE_START" !in t ->
                return Phase.INSTALL
            s.startsWith("重試 GPU") || s.startsWith("安裝 GPU") || ("TURNIP" in t && "WINE_START" !in t && "WINEBOOT_INIT" !in t) ->
                return Phase.GPU
            s.startsWith("啟動進程內") || ("InProcessX" in t && "WINEBOOT_INIT" !in t && "WINE_START" !in t) ->
                return Phase.X_SERVER
            "create_fail=" in t || "ladder advance exhausted" in t || "APP_LOAD_FATAL" in t ->
                return Phase.FAIL_HINT
            "APP_LOAD" in t && "WINE_START" in t ->
                return Phase.APP_LOAD
            "WINE_START" in t && ("MAP_WAIT" in t || "explorer" in t.lowercase()) && "APP_LOAD cmdline" !in t ->
                return Phase.DESKTOP
            "WINE_START" in t ->
                return if ("Loaded L\"" in t && ("App\\app.exe" in t || "App/app.exe" in t)) {
                    Phase.RUNNING
                } else if ("APP_LOAD" in t) {
                    Phase.APP_LOAD
                } else {
                    Phase.DESKTOP
                }
            "RPCSS_PRESTART" in t || s.startsWith("RpcSs") || s.startsWith("正在初始化 RpcSs") ->
                return Phase.RPCSS
            "WINEBOOT_INIT" in t || s.startsWith("正在初始化 Wine") ->
                return Phase.WINEBOOT
            s.startsWith("執行中") ->
                return if ("guestPresented" in t) Phase.RUNNING else Phase.WAIT_PRESENT
            else -> return Phase.GENERIC
        }
    }

    /**
     * 0.1.106: 依真階段／log 指紋選 **一條穩定句**；禁止 tick 輪換。
     * @param metric 可嵌入的短度量（如 t=12s pid=123）；空則純人話
     * @param tick 保留參數相容呼叫端；**不再**用於句庫輪換
     */
    fun pick(logText: String, statusHint: String, tick: Int, metric: String = ""): String {
        val phase = detectPhase(logText, statusHint)
        val pool = pools[phase].orEmpty().ifEmpty { pools.getValue(Phase.GENERIC) }
        val line = phaseBoundLine(phase, logText, pool)
        val m = metric.trim()
        return if (m.isNotEmpty()) "$line（$m）" else line
    }

    /** 真進度綁定：同 phase 內依 stamps 選固定索引，绝不用 tick。 */
    private fun phaseBoundLine(phase: Phase, logText: String, pool: List<String>): String {
        fun at(i: Int, fallback: String = pool.first()) =
            pool.getOrElse(i) { fallback }

        return when (phase) {
            Phase.INSTALL -> at(0)
            Phase.GPU -> when {
                "TURNIP" in logText && "ok" in logText.lowercase() -> at(7)
                else -> at(0)
            }
            Phase.X_SERVER -> when {
                "InProcessX" in logText && "OK" in logText -> at(2)
                else -> at(0)
            }
            Phase.WINEBOOT -> when {
                "WINEBOOT_INIT done" in logText || "wineboot" in logText.lowercase() && "done" in logText.lowercase() -> at(6)
                "system.reg" in logText -> at(1)
                else -> at(0)
            }
            Phase.RPCSS -> when {
                "RpcSs ready" in logText || "ready=1" in logText && "RPCSS" in logText -> at(8)
                "RPCSS_PRESTART" in logText -> at(0)
                else -> at(2)
            }
            Phase.DESKTOP -> when {
                "MAP_WAIT" in logText && "ready" in logText -> at(4)
                "MAP_WAIT" in logText -> at(3)
                else -> at(0)
            }
            Phase.APP_LOAD -> when {
                "Loaded L\"" in logText && ("App\\app.exe" in logText || "App/app.exe" in logText) -> at(6)
                "ladder advance" in logText -> at(5)
                "create_fail=" in logText || "shellex_after_machine14c" in logText -> at(14)
                "machine 14c" in logText || "machine_014c=1" in logText || "machine 0x014c" in logText -> at(8)
                "spawn_evidence=machine_x86_pid" in logText || "mode=start_machine_x86" in logText ||
                    "/machine x86" in logText -> at(2)
                "APP_LOAD cmdline" in logText -> at(0)
                else -> at(0)
            }
            Phase.RUNNING -> when {
                "guestPresented" in logText -> at(2)
                else -> at(0)
            }
            Phase.WAIT_PRESENT -> when {
                "APP_HWND" in logText -> at(6)
                else -> at(0)
            }
            Phase.FAIL_HINT -> when {
                "ladder advance exhausted" in logText -> at(3)
                "create_fail=" in logText -> at(4)
                else -> at(0)
            }
            Phase.GENERIC -> at(0)
        }
    }

    fun poolSize(): Int = pools.values.sumOf { it.size }
}
