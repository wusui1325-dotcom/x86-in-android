package com.wusper.x86.engine

/**
 * 解碼 [NativeEngine.nativeWaitNonBlocking] 回傳值為繁中說明。
 *
 * 編碼約定（native_engine.cpp）：
 * - 1：仍在跑
 * - 0：無子行程
 * - -(1000 + exitStatus)：正常退出，exitStatus 0–255
 * - -(2000 + signal)：被訊號終止
 * - 其餘負值：waitpid errno 等
 */
object ExitStatusMessages {

    data class Decoded(
        val title: String,
        val detail: String,
        val rawCode: Int,
        val exitStatus: Int? = null,
        val signal: Int? = null,
        val isFailure: Boolean,
    )

    fun decode(wait: Int): Decoded {
        when {
            wait == 1 -> return Decoded(
                title = "執行中",
                detail = "客體行程仍在執行。",
                rawCode = wait,
                isFailure = false,
            )
            wait == 0 -> return Decoded(
                title = "未啟動",
                detail = "沒有客體行程。",
                rawCode = wait,
                isFailure = true,
            )
            wait <= -2000 -> {
                val sig = -wait - 2000
                val name = signalName(sig)
                return Decoded(
                    title = "被系統訊號終止",
                    detail = "行程收到訊號 $name（$sig）。常見原因：記憶體不足、強制停止、或原生崩潰。建議重試；若反覆發生請重裝運行時。",
                    rawCode = wait,
                    signal = sig,
                    isFailure = true,
                )
            }
            wait <= -1000 -> {
                val code = -wait - 1000
                return Decoded(
                    title = exitTitle(code),
                    detail = exitDetail(code),
                    rawCode = wait,
                    exitStatus = code,
                    isFailure = code != 0,
                )
            }
            wait < 0 -> return Decoded(
                title = "等待行程失敗",
                detail = "系統回報錯誤碼 $wait。請重試；若持續失敗請重裝運行時。",
                rawCode = wait,
                isFailure = true,
            )
            else -> return Decoded(
                title = "未知狀態",
                detail = "無法解讀狀態碼 $wait。",
                rawCode = wait,
                isFailure = true,
            )
        }
    }

    /** 狀態列短文：人話優先，括號附原始碼。 */
    fun statusLine(wait: Int): String {
        val d = decode(wait)
        return if (d.isFailure) {
            "${d.title}（原始碼 ${d.rawCode}）"
        } else {
            d.title
        }
    }

    private fun exitTitle(code: Int): String = when (code) {
        0 -> "正常結束"
        127 -> "客體命令不存在"
        126 -> "客體命令無法執行"
        1 -> "客體以錯誤結束"
        132 -> "客體非法指令（SIGILL）"
        124 -> "冒煙逾時（Wine PE 活掛）"
        139 -> "Wine PE SIGSEGV（LdrInitializeThunk）"
        134 -> "客體中止（SIGABRT／Wine assert）"
        else -> "客體異常結束"
    }

    private fun exitDetail(code: Int): String = when (code) {
        0 -> "行程已正常結束。"
        127 -> (
            "客體命令不存在（常見：Box64／Wine／系統殼層未裝齊，或舊版連結器問題）。" +
                "請在錯誤卡或首頁選單點「重裝運行時」，無需 adb。"
            )
        126 -> (
            "找到命令但無法執行（權限或格式）。若剛升級或曾清資料失敗，" +
                "請點「重裝運行時」。"
            )
        1 -> (
            "客體行程以退出碼 1 結束（不是 Android「存取所有檔案」權限）。" +
                "若日誌有 c0000018／shared user data：0.1.32+ 改用 x86_64-unix/wine（含 wine_main_preload_info）避免位址衝突；請更新 App 後直接重開。" +
                "否則先看 WINE_VERSION_SMOKE；ntdll／x64lib stamp 缺失才需重裝運行時。"
            )
        132 -> (
            "客體退出碼 132＝SIGILL。0.1.30+ 已換 bookworm libgcc；若仍 132，看 BOX64_SELFTEST／WINE_VERSION_SMOKE。" +
                "SELFTEST=132 → 換 Box64；SMOKE=132 → Wine／x64lib；僅 explorer 炸 → GPU／PE。"
            )
        124 -> (
            "客體退出碼 124＝WINE_PE_SMOKE 逾時。0.1.37+ 版號改讀 wine.inf；PE 探測仍逾時＝loader／wineboot 卡住。" +
                "請確認日誌有 HOLEFIT ok／WINE prereserve of <addr>:<size> done（0.1.39+ 成功即停並快取；不應無限 HOLEFIT try）。"
            )
        139 -> (
            "客體退出碼 139＝Wine PE SIGSEGV。常見 LdrInitializeThunk→KiUserExceptionDispatcher 迴圈：" +
                "Box64 mid prereserve 在 39-bit/proot 下 isBlockFree 失敗會被靜默丟棄。0.1.39+ hole-fit 改 addr/size 且成功即停；" +
                "請蓋裝後看 HOLEFIT ok 與 prereserve mid done。"
            )
        134 -> (
            "客體退出碼 134＝SIGABRT。常見 Wine11 set_mem_area_state granularity_mask assert。" +
                "0.1.38+ 需 wine64k-align + hole-fit mid；看 HOLEFIT ok／BOX64_WINE64K_STAMP。"
            )
        else -> (
            "客體退出碼 $code。請查看日誌；" +
                "若含 linker／libtalloc／permission／ENOENT，請點「重裝運行時」後重試。SIGILL／Illegal instruction 請先看 BOX64_FLAGS（通常不必重裝運行時）。"
            )
    }

    /** 從日誌／例外字串推測是否應引導重裝運行時（#24–26）。 */
    fun shouldSuggestReinstall(detail: String): Boolean {
        val s = detail.lowercase()
        return listOf(
            "permission", "eacces", "權限",
            "libtalloc", "cannot link", "linker",
            "ld-linux", "enoent", "/usr/bin/sh", "not found",
            "box64", "動態連結", "ntdll", "cannot dlopen", "x64lib", "libgcc",
            "illegal instruction", "sigill", "wine_version_smoke", "x64lib_stamp",
        ).any { it in s }
    }

    private fun signalName(sig: Int): String = when (sig) {
        1 -> "SIGHUP"
        2 -> "SIGINT"
        4 -> "SIGILL"
        6 -> "SIGABRT"
        9 -> "SIGKILL"
        11 -> "SIGSEGV"
        15 -> "SIGTERM"
        else -> "signal $sig"
    }
}
