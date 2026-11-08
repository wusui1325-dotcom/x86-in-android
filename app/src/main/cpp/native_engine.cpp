#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <unistd.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <cstring>
#include <cstdlib>
#include "elf_probe.h"

#define LOG_TAG "x86engine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static pid_t g_child = -1;

extern "C" JNIEXPORT jint JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativeProbe(
        JNIEnv* env, jclass, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    char msg[256];
    ElfKind k = probe_elf(path, msg, sizeof(msg));
    env->ReleaseStringUTFChars(jpath, path);
    return static_cast<jint>(k);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativeProbeMessage(
        JNIEnv* env, jclass, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    char msg[256];
    probe_elf(path, msg, sizeof(msg));
    env->ReleaseStringUTFChars(jpath, path);
    return env->NewStringUTF(msg);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativeStop(JNIEnv*, jclass) {
    if (g_child > 0) {
        kill(g_child, SIGTERM);
        usleep(200 * 1000);
        if (kill(g_child, 0) == 0) {
            kill(g_child, SIGKILL);
        }
        int status = 0;
        waitpid(g_child, &status, WNOHANG);
        g_child = -1;
        return 0;
    }
    return -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativeRunningPid(JNIEnv*, jclass) {
    return static_cast<jint>(g_child);
}

/**
 * argv + 可選 env（"KEY=VALUE" 字串陣列，可 null／空）。
 * stdout/stderr → log_path。成功回傳 child pid；失敗回傳 -errno。
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativeExec(
        JNIEnv* env, jclass,
        jobjectArray jargv,
        jstring jcwd,
        jstring jlogPath,
        jobjectArray jenv) {
    if (g_child > 0) {
        LOGE("already running pid=%d", (int)g_child);
        return -EBUSY;
    }
    jsize argc = env->GetArrayLength(jargv);
    if (argc < 1) return -EINVAL;

    std::vector<std::string> args;
    args.reserve(argc);
    for (jsize i = 0; i < argc; ++i) {
        auto js = (jstring)env->GetObjectArrayElement(jargv, i);
        const char* s = env->GetStringUTFChars(js, nullptr);
        args.emplace_back(s ? s : "");
        env->ReleaseStringUTFChars(js, s);
        env->DeleteLocalRef(js);
    }

    std::vector<std::string> envs;
    if (jenv != nullptr) {
        jsize nenv = env->GetArrayLength(jenv);
        envs.reserve(nenv);
        for (jsize i = 0; i < nenv; ++i) {
            auto js = (jstring)env->GetObjectArrayElement(jenv, i);
            const char* s = env->GetStringUTFChars(js, nullptr);
            envs.emplace_back(s ? s : "");
            env->ReleaseStringUTFChars(js, s);
            env->DeleteLocalRef(js);
        }
    }

    const char* cwd = env->GetStringUTFChars(jcwd, nullptr);
    const char* logPath = env->GetStringUTFChars(jlogPath, nullptr);

    int logfd = open(logPath, O_CREAT | O_TRUNC | O_WRONLY, 0644);
    if (logfd < 0) {
        LOGE("open log failed: %s (%d)", logPath, errno);
        env->ReleaseStringUTFChars(jcwd, cwd);
        env->ReleaseStringUTFChars(jlogPath, logPath);
        return -errno;
    }

    pid_t pid = fork();
    if (pid < 0) {
        int e = errno;
        close(logfd);
        env->ReleaseStringUTFChars(jcwd, cwd);
        env->ReleaseStringUTFChars(jlogPath, logPath);
        return -e;
    }
    if (pid == 0) {
        if (cwd && cwd[0]) {
            if (chdir(cwd) != 0) {
                dprintf(logfd, "chdir(%s) failed: %d\n", cwd, errno);
            }
        }
        dup2(logfd, STDOUT_FILENO);
        dup2(logfd, STDERR_FILENO);
        if (logfd > 2) close(logfd);

        for (auto& e : envs) {
            if (e.empty()) continue;
            // putenv needs persistent storage; leak in child is fine
            char* heap = strdup(e.c_str());
            if (heap) putenv(heap);
        }

        std::vector<char*> argv;
        argv.reserve(args.size() + 1);
        for (auto& a : args) argv.push_back(const_cast<char*>(a.c_str()));
        argv.push_back(nullptr);

        execv(argv[0], argv.data());
        dprintf(STDOUT_FILENO, "execv(%s) failed: %d (%s)\n", argv[0], errno, strerror(errno));
        _exit(127);
    }

    close(logfd);
    g_child = pid;
    LOGI("spawned pid=%d cmd=%s env=%zu", (int)pid, args[0].c_str(), envs.size());
    env->ReleaseStringUTFChars(jcwd, cwd);
    env->ReleaseStringUTFChars(jlogPath, logPath);
    return static_cast<jint>(pid);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wusper_x86_engine_NativeEngine_nativeWaitNonBlocking(JNIEnv*, jclass) {
    if (g_child <= 0) return 0;
    int status = 0;
    pid_t r = waitpid(g_child, &status, WNOHANG);
    if (r == 0) return 1;
    if (r == g_child) {
        g_child = -1;
        if (WIFEXITED(status)) return -(1000 + WEXITSTATUS(status));
        if (WIFSIGNALED(status)) return -(2000 + WTERMSIG(status));
        return -1;
    }
    return -errno;
}
