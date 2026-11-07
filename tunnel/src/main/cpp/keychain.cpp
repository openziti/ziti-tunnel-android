/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

#include <jni.h>
#include <string>
#include <functional>

#include <tlsuv/keychain.h>
#include <tlsuv/tls_engine.h>

int android_gen_key(keychain_key_t *pk, enum keychain_key_type type, const char *name);

int android_load_key(keychain_key_t *, const char *name);

int android_rem_key(const char *name);

enum keychain_key_type android_key_type(keychain_key_t k);

int android_key_public(keychain_key_t k, char *buf, size_t *len);

int android_key_sign(keychain_key_t k, const uint8_t *data, size_t datalen,
                     uint8_t *sig, size_t *siglen, int p);

void android_free_key(keychain_key_t k);

struct android_keychain_s {
    keychain_t api;
    jobject store; // AndroidKeyStore
    JavaVM *vm;
};

static android_keychain_s android_keychain{
        .api {
                .gen_key = android_gen_key,
                .load_key = android_load_key,
                .rem_key = android_rem_key,
                .key_type = android_key_type,
                .key_public = android_key_public,
                .key_sign = android_key_sign,
                .free_key = android_free_key,
        }
};
static struct {
    jmethodID loadKey;
    jmethodID keyType;
    jmethodID keyPub;
    jmethodID sign;
    jmethodID genKey;
    jmethodID delKey;
} methods;

static bool checkException(JNIEnv *env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return true;
    }
    return false;
}

// Releases a JNI local reference when it goes out of scope.
//
// The keychain callbacks run on the native-tunnel thread, whose native run() never returns to Java,
// so local references created here are never freed automatically. Each one pins its Java object and
// grows the thread's local reference table until it is deleted explicitly. Holding every local
// reference in a LocalRef releases it on all paths, including early returns.
template<typename T>
class LocalRef {
public:
    LocalRef(JNIEnv *env, T ref) : env(env), ref(ref) {}

    ~LocalRef() {
        // DeleteLocalRef is safe to call with a pending exception
        if (ref != nullptr) env->DeleteLocalRef(ref);
    }

    LocalRef(const LocalRef &) = delete;

    LocalRef &operator=(const LocalRef &) = delete;

    T get() const { return ref; }

private:
    JNIEnv *env;
    T ref;
};

extern "C"
JNIEXPORT void JNICALL
Java_org_openziti_tunnel_Keychain_registerKeychain(JNIEnv *env, jclass clazz, jobject chain) {
    android_keychain.store = env->NewGlobalRef(chain);
    tlsuv_set_keychain(&android_keychain.api);
    env->GetJavaVM(&android_keychain.vm);
    methods.loadKey = env->GetMethodID(clazz, "loadKey",
                                       "(Ljava/lang/String;)Ljava/security/KeyStore$PrivateKeyEntry;");
    methods.keyType = env->GetMethodID(clazz, "keyType",
                                       "(Ljava/security/KeyStore$PrivateKeyEntry;)I");
    methods.keyPub = env->GetMethodID(clazz, "pubKey",
                                      "(Ljava/security/KeyStore$PrivateKeyEntry;)[B");
    methods.sign = env->GetMethodID(clazz, "sign",
                                    "(Ljava/security/KeyStore$PrivateKeyEntry;Ljava/nio/ByteBuffer;)[B");
    methods.genKey = env->GetMethodID(
            clazz, "genKey", "(Ljava/lang/String;Ljava/lang/String;)Ljava/security/KeyStore$PrivateKeyEntry;");
    methods.delKey = env->GetMethodID(clazz, "deleteKey", "(Ljava/lang/String;)V");
}

int android_gen_key(keychain_key_t *pk, enum keychain_key_type type, const char *name) {
    if (android_keychain.store == nullptr) return -1;

    JNIEnv *env;
    android_keychain.vm->GetEnv((void **) &env, JNI_VERSION_1_6);

    const char *type_name;
    if (type == keychain_key_rsa) {
        type_name = "RSA";
    } else if (type == keychain_key_ec) {
        type_name = "EC";
    } else {
        return -1;
    }

    LocalRef<jstring> t(env, env->NewStringUTF(type_name));
    LocalRef<jstring> n(env, env->NewStringUTF(name));
    LocalRef<jobject> key(env, env->CallObjectMethod(android_keychain.store, methods.genKey,
                                                     n.get(), t.get()));
    if (checkException(env) || key.get() == nullptr) {
        return -1;
    }

    *pk = env->NewGlobalRef(key.get());
    return 0;
}

int android_load_key(keychain_key_t *k, const char *name) {
    if (android_keychain.store == nullptr) return -1;

    JNIEnv *env;
    android_keychain.vm->GetEnv((void **) &env, JNI_VERSION_1_6);

    LocalRef<jstring> n(env, env->NewStringUTF(name));
    LocalRef<jobject> key(env, env->CallObjectMethod(android_keychain.store, methods.loadKey,
                                                     n.get()));

    if (checkException(env) || key.get() == nullptr) {
        return -1;
    }

    *k = env->NewGlobalRef(key.get());
    return 0;
}

int android_rem_key(const char *name) {
    if (android_keychain.store == nullptr) {
        return -1;
    }

    JNIEnv *env;
    android_keychain.vm->GetEnv((void **) &env, JNI_VERSION_1_6);

    LocalRef<jstring> n(env, env->NewStringUTF(name));

    // Keychain.deleteKey returns void
    env->CallVoidMethod(android_keychain.store, methods.delKey, n.get());
    if (checkException(env)) {
        return -1;
    }

    return 0;
}

enum keychain_key_type android_key_type(keychain_key_t k) {
    if (k == nullptr) {
        return keychain_key_invalid;
    }
    auto key = (jobject) k;

    JNIEnv *env;
    android_keychain.vm->GetEnv((void **) &env, JNI_VERSION_1_6);
    auto type = (keychain_key_type) env->CallIntMethod(android_keychain.store, methods.keyType,
                                                       key);
    if (checkException(env)) {
        return keychain_key_invalid;
    }
    return type;
}

int android_key_public(keychain_key_t k, char *buf, size_t *len) {
    if (k == nullptr) {
        return -1;
    }
    auto key = (jobject) k;
    JNIEnv *env;
    android_keychain.vm->GetEnv((void **) &env, JNI_VERSION_1_6);
    LocalRef<jbyteArray> b(env, (jbyteArray) env->CallObjectMethod(android_keychain.store,
                                                                    methods.keyPub, key));

    if (checkException(env) || b.get() == nullptr) {
        return -1;
    }

    auto size = env->GetArrayLength(b.get());
    if (size > *len) {
        return -1;
    }
    auto bytes = env->GetByteArrayElements(b.get(), nullptr);
    memcpy(buf, bytes, size);
    *len = size;
    env->ReleaseByteArrayElements(b.get(), bytes, JNI_ABORT);

    return 0;
}

int android_key_sign(keychain_key_t k, const uint8_t *data, size_t datalen,
                     uint8_t *sig, size_t *siglen, int p) {
    if (k == nullptr) {
        return -1;
    }

    auto key = (jobject) k;
    JNIEnv *env;
    android_keychain.vm->GetEnv((void **) &env, JNI_VERSION_1_6);

    LocalRef<jobject> d(env, env->NewDirectByteBuffer((void *) data, datalen));
    LocalRef<jbyteArray> s(env, (jbyteArray) env->CallObjectMethod(android_keychain.store,
                                                                   methods.sign, key, d.get()));
    if (checkException(env) || s.get() == nullptr) {
        return -1;
    }

    jsize size = env->GetArrayLength(s.get());
    *siglen = size;
    auto bytes = env->GetByteArrayElements(s.get(), nullptr);
    memcpy(sig, bytes, size);
    env->ReleaseByteArrayElements(s.get(), bytes, JNI_ABORT);
    return 0;
}

void android_free_key(keychain_key_t k) {
    if (k == nullptr) {
        return;
    }
    auto key = (jobject) k;
    JNIEnv *env;
    android_keychain.vm->GetEnv((void **) &env, JNI_VERSION_1_6);
    env->DeleteGlobalRef(key);
    checkException(env);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_openziti_tunnel_Keychain_testNativeKey(JNIEnv *env, jclass, jstring name) {
    auto tls = default_tls_context();
    std::unique_ptr<tls_context, std::function<void(tls_context * )>> p(
            tls, [](tls_context *t) { t->free_ctx(t); }
    );

    tlsuv_private_key_t key = nullptr;
    auto key_name = env->GetStringUTFChars(name, nullptr);
    int rc = tls->load_keychain_key(&key, key_name);
    env->ReleaseStringUTFChars(name, key_name);
    if (rc != 0) {
        return false;
    }

    auto pub = key->pubkey(key);
    if (pub != nullptr) {
        pub->free(pub);
    }

    const char *msg = "this is a message";
    char sig[512];
    size_t siglen = sizeof(sig);
    key->sign(key, hash_SHA256, msg, strlen(msg), sig, &siglen);

    key->free(key);
    return true;
}