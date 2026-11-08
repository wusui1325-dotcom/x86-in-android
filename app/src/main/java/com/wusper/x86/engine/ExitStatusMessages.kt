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
            "客體行程以退出碼 1 結束。請查看下方日誌。" +
                "若出現 could not load ntdll.so／Cannot dlopen、權限被拒、或缺動態庫，" +
                "請優先點「重裝運行時」（不要只改系統儲存權限）。"
            )
        132 -> (
            "客體退出碼 132＝SIGILL（Illegal instruction）。0.1.28+ 已強制 " +
                "BOX64_DYNAREC_NOHOSTEXT=1（dynarec 只用 ASIMD）並關閉 AVX／SSE42／AES。" +
                "請看日誌是否有 BOX64_SELFTEST／BOX64_FLAGS；若 SELFTEST 即 132，需換 Box64 組件。" +
                "更新 App 後重開即可（腳本會自動覆寫）；仍失敗再「重裝運行時」。"
            )
        else -> (
            "客體退出碼 $code。請查看日誌；" +
                "若含 linker／libtalloc／permission／ENOENT／Illegal instruction，請點「重裝運行時」後重試。"
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
            "illegal instruction", "sigill",
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
