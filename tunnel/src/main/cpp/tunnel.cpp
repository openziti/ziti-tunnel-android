#include <jni.h>
#include <string>

#include <tlsuv/tlsuv.h>
#include <ziti/ziti.h>
#include <ziti/ziti_tunnel.h>

static jstring JNICALL tlsuvVersion(JNIEnv *env, jobject /* this */);
static jstring JNICALL zitiSdkVersion(JNIEnv *env, jobject self);
static jstring JNICALL zitiTunnelVersion(JNIEnv *env, jobject self);

extern "C"
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Find your class. JNI_OnLoad is called from the correct class loader context for this to work.
    jclass c = env->FindClass("org/openziti/tunnel/Tunnel");
    if (c == nullptr) return JNI_ERR;

    // Register your class' native methods.
    static const JNINativeMethod methods[] = {
            {"tlsuvVersion", "()Ljava/lang/String;", reinterpret_cast<void*>(tlsuvVersion)},
            {"zitiSdkVersion", "()Ljava/lang/String;", (void*)(zitiSdkVersion)},
            {"zitiTunnelVersion", "()Ljava/lang/String;", (void*)(zitiTunnelVersion)},
    };
    int rc = env->RegisterNatives(c, methods, sizeof(methods)/sizeof(JNINativeMethod));
    if (rc != JNI_OK) return rc;

    return JNI_VERSION_1_6;
}

jstring JNICALL tlsuvVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(tlsuv_version());
}

jstring JNICALL zitiSdkVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(ziti_get_version()->version);
}

jstring JNICALL zitiTunnelVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(ziti_tunneler_version());
}


