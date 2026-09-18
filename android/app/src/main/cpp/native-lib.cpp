#include <jni.h>
#include <string>
#include <cstring>
#include <vector>
#include <cstdlib>
#include <mutex>
#include <atomic>
#include <new>

namespace node {
// Forward declaration - native-lib only needs node::Start(argv)
int Start(int argc, char* argv[]);
}

// Ensures the embedded Node.js runtime is started exactly once per process.
// Calling node::Start() more than once in the same process double-initializes
// V8 and segfaults, so this guard (std::call_once + atomic) makes it impossible.
static std::once_flag s_nodeStarted;
static std::atomic<bool> s_nodeThreadSpawned{false};

extern "C" JNIEXPORT jint JNICALL
Java_dev_mstheesha_afk_NodeRuntime_startNodeWithArguments(
    JNIEnv* env, jobject /* this */, jobjectArray args) {

    // Refuse to double-start Node within the same process.
    bool expected = false;
    if (!s_nodeThreadSpawned.compare_exchange_strong(expected, true)) {
        return -1;
    }

    jsize argc = env->GetArrayLength(args);
    if (argc <= 0) return -1;

    //
    // PROCESS-LIFETIME argv storage.
    //
    // node::Start() does NOT return when initialisation is done. It blocks and
    // runs the process event loop for the whole lifetime of the embedded
    // runtime, and Node/V8 keeps reading these argv pointers the entire time
    // (they become process.argv, and V8 references them at startup and during
    // teardown). The previous code freed every argv string the moment Start
    // returned, so the moment one of V8's own mutex-protected sections was
    // re-locked from an internal worker thread, libc FORTIFY detected "lock on
    // a destroyed mutex" (the mutex lives inside the freed V8/Node object) and
    // aborted the process with SIGABRT ~2s after Start.
    //
    // These buffers are intentionally leaked for the lifetime of the process —
    // freeing them is neither needed (the OS reclaims everything at exit) nor
    // possible (Node still owns them). A single static owner, written exactly
    // once under call_once, can never be raced by Kotlin-side helper threads.
    //
    static std::vector<char*> s_argv;
    static std::string s_argcStorage;
    static char** s_processArgv = nullptr;
    static int s_argc = 0;

    if (s_processArgv == nullptr) {
        s_argv.reserve(argc);
        for (jsize i = 0; i < argc; i++) {
            jstring jstr = (jstring)env->GetObjectArrayElement(args, i);
            const char* cstr = env->GetStringUTFChars(jstr, nullptr);
            char* dup = ::strdup(cstr);
            s_argv.push_back(dup);
            env->ReleaseStringUTFChars(jstr, cstr);
            env->DeleteLocalRef(jstr);
        }
        s_processArgv = s_argv.data();
        s_argc = argc;
    }

    int result = -2;
    std::call_once(s_nodeStarted, [&]() {
        result = node::Start(s_argc, s_processArgv);
    });

    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mstheesha_afk_NodeRuntime_isNodeStarted(
    JNIEnv* /* env */, jobject /* this */) {
    return s_nodeThreadSpawned.load();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mstheesha_afk_NodeRuntime_initNodeEnvironment(
    JNIEnv* env, jobject /* this */, jstring filesDir) {

    const char* dir = env->GetStringUTFChars(filesDir, nullptr);
    // Set NODE_PATH to include node_modules
    std::string nodePath = std::string(dir) + "/nodejs-project/node_modules";
    setenv("NODE_PATH", nodePath.c_str(), 1);
    env->ReleaseStringUTFChars(filesDir, dir);
}