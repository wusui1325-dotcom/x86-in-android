package com.wusper.x86.session

/**
 * 0.1.107 UX：藍屏（desktopOnly／App 未 Loaded）人話錯誤說明。
 * ≥50 句繁中備庫；依失敗原因**鎖住**挑選（同 reason 指紋同句，不每秒輪換）；
 * 可同時顯示多句；含經典：（；永不空白（fallback GENERIC）。
 * 不取代 P0 A2；僅 Present／HUD 疊字。
 */
object BlueScreenMessages {

    /** 0.1.105 UX：藍屏固定末行（永不省略／不輪替） */
    const val FOOTER =
        "我們既沒有嘗試在收集你的問題，也不會提供二維碼和支援聯繫"

    enum class Reason {
        MACHINE_ZERO,
        NO_STARTED_PID,
        CREATOR_DETACH,
        LOADED_ZERO,
        PATH_DBLSLASH,
        RPCSS,
        GATE,
        MAP_WAIT,
        LADDER_EXHAUSTED,
        SEED_MISSING,
        GENERIC,
    }

    data class Pick(
        val lines: List<String>,
        val reasons: List<Reason>,
    )

    private val pools: Map<Reason, List<String>> = mapOf(
        Reason.MACHINE_ZERO to listOf(
            "程式映像類型對不上，客體起不來：（",
            "32 位元 App 被當成未知映像，啟動停在門口。",
            "Wow64 機器碼是 0，不是預期的 0x014c。",
            "系統認不出這支 PE 的 CPU 類型。",
            "映像機型欄是空的，建立行程失敗了。",
            "好像少了 32 位元相容層的一環。",
            "App 的 machine 欄位沒帶上，只好卡住。",
            "位元模式對不齊，桌面只好乾等。",
        ),
        Reason.NO_STARTED_PID to listOf(
            "行程建立了申請，但沒有拿到 PID：（",
            "CreateProcess 走到一半就沒下文。",
            "App 沒有真正被拉起來（無 started pid）。",
            "建立要求有送出，子行程卻沒出現。",
            "啟動握手失敗，桌面還是空的。",
            "系統說要開 App，結果沒人應門。",
            "行程票根是空的，畫面只好發呆。",
            "啟動鏈斷在「已請求、未誕生」。",
        ),
        Reason.CREATOR_DETACH to listOf(
            "啟動器自己先離開了（creator detach）：（",
            "建立端在映像被拒後就收工了。",
            "mscoree／關機路徑被觸發，App 沒跟上。",
            "建立者行程先 LdrShutdown，客體沒留下。",
            "啟動助手中途解散，桌面只剩藍底。",
            "建立端判定映像無效後就撤退了。",
            "協助啟動的行程先走，App 沒接手。",
        ),
        Reason.LOADED_ZERO to listOf(
            "還沒看到 App 被載入（Loaded=0）：（",
            "模組清單裡沒有 C:\\App\\app.exe。",
            "桌面在，遊戲／程式本體還沒進場。",
            "載入計數仍是零，所以畫面空白。",
            "系統還沒把 App 映射進記憶體。",
            "等了又等，Loaded 燈號還是暗的。",
            "客體 PE 沒進場，Present 只能發呆。",
            "藍屏多半因為 App 根本沒 Loaded。",
        ),
        Reason.PATH_DBLSLASH to listOf(
            "路徑多了一道反斜線，啟動可能走歪：（",
            "偵測到 C:\\App\\\\app.exe 這種雙斜線。",
            "路徑衛生檢查沒過（dblslash）。",
            "檔名路徑被疊成奇怪的雙反斜線。",
            "請確認 App 路徑是單反斜線形式。",
        ),
        Reason.RPCSS to listOf(
            "RPCSS／COM 服務還沒就緒：（",
            "RpcSs 沒進 RUNNING，App 先別急。",
            "遠端程序呼叫服務卡住了。",
            "系統服務層沒亮綠燈。",
            "COM 基礎設施還沒站穩。",
        ),
        Reason.GATE to listOf(
            "啟動閘門還沒放行（RPCSS 或桌面未就緒）。",
            "硬閘檢查沒過，暫時不拉 App。",
            "桌面或 RPC 其中一邊還沒 ready。",
            "安全閘門擋下來，避免在半殘狀態開 App。",
        ),
        Reason.MAP_WAIT to listOf(
            "桌面映射還在等（MAP_WAIT）：（",
            "虛擬桌面還沒完全就緒。",
            "顯示桌面標記還沒收齊。",
            "等桌面亮起的時間還沒到。",
        ),
        Reason.LADDER_EXHAUSTED to listOf(
            "啟動階梯試完了，還是沒 Loaded：（",
            "machine／direct／explorer／unix／start 都試過。",
            "備援路徑也用盡了，需要再挖 log。",
            "階梯爬到頂還是空白桌面。",
            "每一階都沒把 App 帶起來。",
        ),
        Reason.SEED_MISSING to listOf(
            "找不到 C:\\App\\app.exe 種子檔：（",
            "App 種子沒就位，無法建立行程。",
            "磁碟上的 App 複本是空的或缺的。",
            "請確認遊戲／程式檔有正確放入。",
        ),
        Reason.GENERIC to listOf(
            "客體畫面還沒來，先看這行說明：（",
            "藍屏＝桌面在、App 不在。",
            "目前只看到空桌面，沒有 App 視窗。",
            "啟動卡關了，正在把原因說清楚。",
            "別慌，這是說明層，不是死機。",
            "程式還沒上桌，所以先顯示提示。",
            "若一直停在這，請匯出 log 給我們。",
            "我們會依實際失敗原因換句人話。",
            "空藍底多半是 App 沒起來，不是顯示壞掉。",
            "再試一次前，先讀一下這些短句。",
            "客體還在準備，或已經失敗需要重試。",
            "提示會隨失敗類型更換，不是固定一句。",
        ),
    )

    init {
        val total = pools.values.sumOf { it.size }
        require(total >= 50) { "BlueScreenMessages pool size $total < 50" }
    }

    fun detectReasons(logText: String): List<Reason> {
        val out = linkedSetOf<Reason>()
        val t = logText
        if ("machine_zero=1" in t || "machine 0" in t && "App" in t) out += Reason.MACHINE_ZERO
        if ("create_fail=no_started_pid" in t || "started_pid_after_app=0" in t) out += Reason.NO_STARTED_PID
        if ("creator_detach_after_machine0" in t) out += Reason.CREATOR_DETACH
        if ("loaded_app=0" in t || "Loaded L\"C:\\App\\app.exe\"" !in t && "APP_LOAD" in t) {
            if ("loaded_app=0" in t || "0× Loaded" in t || "loaded=0" in t) out += Reason.LOADED_ZERO
        }
        if ("dblslash_hits=" in t && !t.contains("dblslash_hits=0")) out += Reason.PATH_DBLSLASH
        if ("path_hygiene=dblslash" in t) out += Reason.PATH_DBLSLASH
        if ("rpc_ready=0" in t || "RPCSS_PRESTART" in t && "ready=0" in t) out += Reason.RPCSS
        if ("gate=fail" in t || "APP_LOAD_FATAL gate" in t) out += Reason.GATE
        if ("MAP_WAIT" in t && ("timeout" in t || "ready=0" in t)) out += Reason.MAP_WAIT
        if ("ladder advance exhausted" in t) out += Reason.LADDER_EXHAUSTED
        if ("APP_SEED gate=FAIL" in t || "seed_gate" in t && "FAIL" in t) out += Reason.SEED_MISSING
        if (out.isEmpty()) out += Reason.GENERIC
        // Always allow a soft generic companion line when multiple reasons
        if (out.size == 1 && out.first() != Reason.GENERIC) {
            // keep single primary
        }
        return out.toList()
    }

    /**
     * 0.1.107: pick **locked** to reason fingerprint — same reasons → same lines.
     * [salt] ignored (kept for call-site compat); **no** per-second pool walk.
     * Always returns ≥1 human line containing `：（` + fixed [FOOTER].
     */
    @Suppress("UNUSED_PARAMETER")
    fun pick(logText: String, maxLines: Int = 3, salt: Int = 0): Pick {
        val reasons = detectReasons(logText)
        // Stable seed from reason set only (NOT wall-clock / PRESENT poll salt)
        val reasonKey = reasons.joinToString(",") { it.name }
        val stable = reasonKey.hashCode()
        val lines = ArrayList<String>(maxLines + 1)
        for ((idx, reason) in reasons.withIndex()) {
            if (lines.size >= maxLines) break
            val pool = pools[reason].orEmpty()
            if (pool.isEmpty()) continue
            // Fixed index per reason+fingerprint — never advances with time
            val line = pool[(stable + idx * 7 + reason.ordinal * 3).floorMod(pool.size)]
            if (line !in lines) lines += line
        }
        // Fill remaining from GENERIC / LOADED_ZERO with same stable seed
        if (lines.size < maxLines) {
            val fill = pools[Reason.GENERIC].orEmpty() + pools[Reason.LOADED_ZERO].orEmpty()
            var i = 0
            while (lines.size < maxLines && fill.isNotEmpty()) {
                val cand = fill[(stable + i * 5).floorMod(fill.size)]
                if (cand !in lines) lines += cand
                i++
                if (i > fill.size + 8) break
            }
        }
        // Never blank: guarantee ≥1 human line
        if (lines.isEmpty()) {
            lines += pools[Reason.GENERIC]!!.first()
        }
        // Guarantee classic emoticon appears at least once
        if (lines.none { "：（" in it }) {
            lines[0] = lines[0].trimEnd('。', '…', ' ') + "：（"
        }
        // Fixed footer always last (Boss UX)
        lines.removeAll { it == FOOTER }
        lines += FOOTER
        return Pick(lines = lines, reasons = reasons)
    }

    /** Reason fingerprint for SessionActivity cache — same fail → same overlay. */
    fun reasonFingerprint(logText: String): String =
        detectReasons(logText).joinToString("|") { it.name }

    private fun Int.floorMod(m: Int): Int {
        if (m <= 0) return 0
        val r = this % m
        return if (r >= 0) r else r + m
    }
}
