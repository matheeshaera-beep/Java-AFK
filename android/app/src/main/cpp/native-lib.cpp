#include <jni.h>
#include <string>
#include <cstring>
#include <vector>
#include <cstdlib>

namespace node {
// Forward declaration - native-lib only needs node::Start(argv)
int Start(int argc, char* argv[]);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_mstheesha_afk_JavaEngine_startNodeWithArguments(
    JNIEnv* env, jobject /* this */, jobjectArray args) {
    
    // Convert Java String[] to C argc/argv
    jsize argc = env->GetArrayLength(args);
    if (argc <= 0) return -1;
    
    std::vector<char*> argv;
    argv.reserve(argc);
    
    for (jsize i = 0; i < argc; i++) {
        jstring jstr = (jstring)env->GetObjectArrayElement(args, i);
        const char* cstr = env->GetStringUTFChars(jstr, nullptr);
        argv.push_back(strdup(cstr));
        env->ReleaseStringUTFChars(jstr, cstr);
        env->DeleteLocalRef(jstr);
    }
    
    // Start Node.js (libuv event loop is initialized internally)
    int result = node::Start(argc, argv.data());
    
    // Free argv strings
    for (char* arg : argv) {
        free(arg);
    }
    
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mstheesha_afk_JavaEngine_initNodeEnvironment(
    JNIEnv* env, jobject /* this */, jstring filesDir) {
    
    const char* dir = env->GetStringUTFChars(filesDir, nullptr);
    // Set NODE_PATH to include node_modules
    std::string nodePath = std::string(dir) + "/nodejs-project/node_modules";
    setenv("NODE_PATH", nodePath.c_str(), 1);
    env->ReleaseStringUTFChars(filesDir, dir);
}
