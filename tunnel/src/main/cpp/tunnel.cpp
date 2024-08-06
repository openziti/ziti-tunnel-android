#include <jni.h>
#include <string>
#include <android/log.h>

#include <tlsuv/keychain.h>
#include <tlsuv/tlsuv.h>
#include <tlsuv/tls_engine.h>
#include <ziti/ziti.h>
#include <ziti/ziti_dns.h>
#include <ziti/ziti_log.h>
#include <ziti/ziti_tunnel.h>
#include "ziti/ziti_tunnel_cbs.h"

extern netif_driver android_netif_driver();

static void JNICALL init_tunnel(JNIEnv *, jobject, jstring, jstring);
static void JNICALL setup_ipc(JNIEnv *, jobject, jint, jint);
static void JNICALL run_tunnel(JNIEnv *env, jobject);
static void JNICALL execute_cmd(JNIEnv *, jobject, jstring, jstring, jobject);
static jstring JNICALL tlsuvVersion(JNIEnv *env, jobject /* this */);
static jstring JNICALL zitiSdkVersion(JNIEnv *env, jobject self);
static jstring JNICALL zitiTunnelVersion(JNIEnv *env, jobject self);
static void notify_cb(uv_async_t *async);
static void android_logger(int, const char *loc, const char *msg, size_t msglen);
static void on_event(const base_event *);

struct cmd_entry {
    tunnel_command cmd;
    jobject ctx;
    TAILQ_ENTRY(cmd_entry) _next;
};

static TAILQ_HEAD(cmd_queue, cmd_entry) cmd_queue;
static uv_mutex_t cmd_queue_lock;

static uv_loop_t *loop;
static uv_async_t notify;
static const ziti_tunnel_ctrl *CTRL;
static uv_pipe_t cmd_pipe;
static uv_pipe_t event_pipe;

struct {
    JavaVM* vm;
    jobject tunnel;

    jmethodID addRoute;
    jmethodID delRoute;
    jmethodID commitRoutes;
    jmethodID onEvent;
    jmethodID onResp;
} tunnelMethods;

extern "C"
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    tunnelMethods.vm = vm;
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Find your class. JNI_OnLoad is called from the correct class loader context for this to work.
    jclass c = env->FindClass("org/openziti/tunnel/Tunnel");
    if (c == nullptr) return JNI_ERR;

    // Register your class' native methods.
    static const JNINativeMethod methods[] = {
            {"run",               "()V",                                                       (void *) run_tunnel},
            {"tlsuvVersion",      "()Ljava/lang/String;",                                      (void *) (tlsuvVersion)},
            {"zitiSdkVersion",    "()Ljava/lang/String;",                                      (void *) (zitiSdkVersion)},
            {"zitiTunnelVersion", "()Ljava/lang/String;",                                      (void *) (zitiTunnelVersion)},
            {"executeCommand",    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V", (void *) execute_cmd},
            {"initNative", "(Ljava/lang/String;Ljava/lang/String;)V", (void*) init_tunnel},
            {"setupIPC", "(II)V", (void*) setup_ipc},
    };
    int rc = env->RegisterNatives(c, methods, sizeof(methods) / sizeof(JNINativeMethod));
    if (rc != JNI_OK) return rc;

    tunnelMethods.addRoute = env->GetMethodID(c, "addRoute", "(Ljava/lang/String;)V");
    tunnelMethods.delRoute = env->GetMethodID(c, "delRoute", "(Ljava/lang/String;)V");
    tunnelMethods.commitRoutes = env->GetMethodID(c, "commitRoutes", "()V");
    tunnelMethods.onEvent = env->GetMethodID(c, "onEvent", "(Ljava/lang/String;)V");
    tunnelMethods.onResp = env->GetMethodID(c, "onResponse", "(Ljava/lang/String;Ljava/util/concurrent/CompletableFuture;)V");
    return JNI_VERSION_1_6;
}

void init_tunnel(JNIEnv *env, jobject self, jstring app, jstring ver) {
    auto app_name = env->GetStringUTFChars(app, nullptr);
    auto app_ver = env->GetStringUTFChars(ver, nullptr);
    ziti_set_app_info(app_name, app_ver);
    env->ReleaseStringUTFChars(app, app_name);
    env->ReleaseStringUTFChars(ver, app_ver);

    uv_mutex_init(&cmd_queue_lock);
    TAILQ_INIT(&cmd_queue);
    
    tunnelMethods.tunnel = env->NewGlobalRef(self);
    loop = uv_loop_new();
    ziti_log_init(loop, DEBUG, android_logger);
    uv_async_init(loop, &notify, notify_cb);
    uv_pipe_init(loop, &cmd_pipe, 0);
    uv_pipe_init(loop, &event_pipe, 0);

    tunneler_sdk_options tunneler_opts = {
            .netif_driver = android_netif_driver(),
            .ziti_dial = ziti_sdk_c_dial,
            .ziti_close = ziti_sdk_c_close,
            .ziti_close_write = ziti_sdk_c_close_write,
            .ziti_write = ziti_sdk_c_write,
            .ziti_host = ziti_sdk_c_host
    };
    
    tunneler_context tun_ctx = ziti_tunneler_init(&tunneler_opts, loop);
    CTRL = ziti_tunnel_init_cmd(loop, tun_ctx, on_event);
    ziti_dns_setup(tun_ctx, "100.64.0.2", "100.64.0.0/10");
}

static void JNICALL setup_ipc(JNIEnv *, jobject, jint cmd_fd, jint event_fd) {
    uv_pipe_open(&cmd_pipe, cmd_fd);
    uv_pipe_open(&event_pipe, event_fd);
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

static void cmd_write_cb(uv_write_t *req, int i) {
    free(req->data);
    free(req);
}

static void cmd_done(const tunnel_result *res, void *) {
    uv_buf_t buf;
    buf.base = tunnel_result_to_json(res, MODEL_JSON_COMPACT, &buf.len);

    auto wr = (uv_write_t*)calloc(1, sizeof(uv_write_t));
    wr->data = buf.base;
    uv_write(wr, (uv_stream_t *)&cmd_pipe, &buf, 1, cmd_write_cb);
}

static void cmd_alloc(uv_handle_t *, size_t i, uv_buf_t *buf) {
    static char cmd_buf[4096];
    buf->base = cmd_buf;
    buf->len = sizeof(cmd_buf);
}

static void cmd_data(uv_stream_t *s, ssize_t len, const uv_buf_t *buf) {
    json_tokener *tokener;
    if (s->data == nullptr) {
        tokener = json_tokener_new();
        s->data = tokener;
    } else {
        tokener = (json_tokener*)s->data;
    }

    json_object *json = json_tokener_parse_ex(tokener, buf->base, len);
    if (json) {
        tunnel_command cmd{};
        tunnel_command_from_json(&cmd, json);
        CTRL->process(&cmd, cmd_done, nullptr);
    }
}

void run_tunnel(JNIEnv *env, jobject self) {
    ZITI_LOG(INFO, "starting Ziti run loop");
    uv_read_start((uv_stream_t*)&cmd_pipe, cmd_alloc, cmd_data);
    uv_run(loop, UV_RUN_DEFAULT);
    ZITI_LOG(INFO, "terminated Ziti run loop");
}

static void on_cmd_complete(const tunnel_result *res, void *ctx) {
    auto json = tunnel_result_to_json(res, MODEL_JSON_COMPACT, nullptr);

    JNIEnv *env;
    tunnelMethods.vm->GetEnv((void**)&env, JNI_VERSION_1_6);
    auto str = env->NewStringUTF(json);

    auto o = (jobject)ctx;
    env->CallVoidMethod(tunnelMethods.tunnel, tunnelMethods.onResp, str, o);
    env->DeleteGlobalRef(o);
}

void notify_cb(uv_async_t *a) {
    uv_mutex_lock(&cmd_queue_lock);
    while(!TAILQ_EMPTY(&cmd_queue)) {
        cmd_entry *cmd = TAILQ_FIRST(&cmd_queue);
        TAILQ_REMOVE(&cmd_queue, cmd, _next);

        CTRL->process(&cmd->cmd, on_cmd_complete, cmd->ctx);
        free_tunnel_command(&cmd->cmd);
        free(cmd);
    }
    uv_mutex_unlock(&cmd_queue_lock);
}

static void JNICALL execute_cmd(JNIEnv *env, jobject self, jstring cmd, jstring data, jobject ctx) {
    auto c = (cmd_entry*)calloc(1,sizeof(cmd_entry));
    c->ctx = env->NewGlobalRef(ctx);

    auto cmd_str = env->GetStringUTFChars(cmd, nullptr);
    c->cmd.command = TunnelCommands.value_of(cmd_str);
    env->ReleaseStringUTFChars(cmd, cmd_str);
    
    auto data_str = env->GetStringUTFChars(data, nullptr);
    c->cmd.data = strdup(data_str);
    env->ReleaseStringUTFChars(data, data_str);

    uv_mutex_lock(&cmd_queue_lock);
    TAILQ_INSERT_TAIL(&cmd_queue, c, _next);
    uv_mutex_unlock(&cmd_queue_lock);
    uv_async_send(&notify);
}

static void on_event(const base_event *ev) {
    char *json = nullptr;
    switch (ev->event_type) {
        case TunnelEvent_ContextEvent:
            json = ziti_ctx_event_to_json(
                    (const ziti_ctx_event*)ev, MODEL_JSON_COMPACT, nullptr);
            break;
        case TunnelEvent_ServiceEvent:
            json = service_event_to_json(
                    (const service_event *)ev, MODEL_JSON_COMPACT, nullptr);
            break;
        case TunnelEvent_MFAEvent:
            json = mfa_event_to_json(
                    (const mfa_event*)ev, MODEL_JSON_COMPACT, nullptr);
            break;
        case TunnelEvent_MFAStatusEvent:
            json = mfa_event_to_json(
                    (const mfa_event*)ev, MODEL_JSON_COMPACT, nullptr);
            break;
        case TunnelEvent_APIEvent:
            json = api_event_to_json(
                    (const api_event*)ev, MODEL_JSON_COMPACT, nullptr);
            break;
        // TODO case TunnelEvent_ExtJWTEvent:
        case TunnelEvent_Unknown:
        default:
            break;
    }

    if (json != nullptr) {
        JNIEnv *env;
        tunnelMethods.vm->GetEnv((void **) &env, JNI_VERSION_1_6);
        jstring evString = env->NewStringUTF(json);
        env->CallVoidMethod(tunnelMethods.tunnel,
                            tunnelMethods.onEvent, evString);
        env->DeleteLocalRef(evString);
        free(json);
    }
}

int tunnel_add_route(netif_handle, const char *route) {
    JNIEnv *env;
    tunnelMethods.vm->GetEnv((void **) &env, JNI_VERSION_1_6);
    jstring evString = env->NewStringUTF(route);
    env->CallVoidMethod(tunnelMethods.tunnel, tunnelMethods.addRoute, evString);
    return 0;
}

int tunnel_del_route(netif_handle, const char *route) {
    JNIEnv *env;
    tunnelMethods.vm->GetEnv((void **) &env, JNI_VERSION_1_6);
    jstring evString = env->NewStringUTF(route);
    env->CallVoidMethod(tunnelMethods.tunnel, tunnelMethods.delRoute, evString);
    env->DeleteLocalRef(evString);
    return 0;
}

int tunnel_commit(netif_handle, uv_loop_t *) {
    JNIEnv *env;
    tunnelMethods.vm->GetEnv((void **) &env, JNI_VERSION_1_6);
    env->CallVoidMethod(tunnelMethods.tunnel, tunnelMethods.commitRoutes);
    return 0;
}

static void android_logger(int level, const char *loc, const char *msg, size_t msglen) {
    int pri = ANDROID_LOG_DEFAULT;
    switch ((DebugLevel)level) {
        case ERROR: pri = ANDROID_LOG_ERROR; break;
        case WARN: pri = ANDROID_LOG_WARN; break;
        case INFO: pri = ANDROID_LOG_INFO; break;
        case DEBUG: pri = ANDROID_LOG_DEBUG; break;
        case NONE: pri = ANDROID_LOG_SILENT; break;
        case TRACE:
        case VERBOSE: pri = ANDROID_LOG_VERBOSE; break;
    }
    __android_log_print(pri, loc, "%.*s", (int)msglen, msg);
}