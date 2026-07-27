#include "FileJNI.h"
#include "FileCache.h"
#include "Utils.h"

#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <vector>
#include <atomic>
#include <shadowhook.h>
#include <dirent.h>
#include "FileSAF.h"

#define LOGI(...) ((void)0)
#define LOGW(...) ((void)0)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "HOOK", __VA_ARGS__)

#if 0
#define MUTEX_LOCK   pthread_mutex_lock(&lock);
#define MUTEX_UNLOCK pthread_mutex_unlock(&lock);
#else
#define MUTEX_LOCK
#define MUTEX_UNLOCK
#endif

static JavaVM* m_jvm = nullptr;
static pthread_mutex_t lock;
static bool posixCallsWasHooked = false;

extern std::atomic<bool> g_safEnabled;
extern FILE *(*real_fopen)(const char *, const char *);
extern int (*real_open)(const char *, int, ...);
extern int (*real_fclose)(FILE *);
extern int (*real_close)(int);
extern int (*real_mkdir)(const char *, mode_t);
extern int (*real_stat)(const char *, struct stat *);
extern int (*real_access)(const char *, int);
extern int (*real_remove)(const char *);
extern struct dirent *(*real_readdir)(DIR *);
extern DIR *(*real_opendir)(const char *);
extern int (*real_readdir_r)(DIR *,
                             struct dirent *,
                             struct dirent **);
extern int (*real_closedir)(DIR *);
extern int (*real_scandir)(const char *,
                           struct dirent ***,
                           int (*)(const struct dirent *),
                           int (*)(const struct dirent **,
                                   const struct dirent **));
extern int (*real_rename)(const char *,
                          const char *);
extern int (*real_chdir)(const char *);
extern char *(*real_getcwd)(char* const buf, size_t size);

extern "C" void *loadRealFunc(const char *name);

template <typename Fn>
static bool hook_by_name(const char* lib_name,
                         const char* sym_name,
                         Fn replacement,
                         Fn* original,
                         uint32_t flags = SHADOWHOOK_HOOK_DEFAULT)
{
    void* stub = nullptr;

    if (flags == SHADOWHOOK_HOOK_DEFAULT) {
        stub = shadowhook_hook_sym_name(lib_name, sym_name,
                                        (void*)replacement,
                                        (void**)original);
    } else {
        stub = shadowhook_hook_sym_name_2(lib_name, sym_name,
                                          (void*)replacement,
                                          (void**)original,
                                          flags);
    }

    if (!stub) {
        int err = shadowhook_get_errno();
        LOGE("hook_by_name failed: %d - %s", err, shadowhook_to_errmsg(err));
        return false;
    }

    return true;
}

static bool getEnv(JNIEnv **jniEnv) {
	if (m_jvm == nullptr) {
		LOGI("ERROR: JVM is null");
		return false;
	}

	int status = m_jvm->GetEnv((void **)jniEnv, JNI_VERSION_1_6);
	if (status == JNI_EDETACHED) {
		status = m_jvm->AttachCurrentThread(jniEnv, nullptr);
		if (status != JNI_OK) {
			LOGI("ERROR: failed to attach thread");
			return false;
		}
		return true;
	}
	return false;
}

static jclass FileJNI_cls;
static jmethodID fopen_method;
static jmethodID mkdir_method;
static jmethodID exists_method;
static jmethodID delete_method;
static jmethodID opendir_method;
static jmethodID rename_method;

extern "C" __attribute__((used)) __attribute__((visibility("default"))) jint JNI_OnLoad(JavaVM* vm, void* reserved) {
	m_jvm = vm;
	pthread_mutex_init(&lock, NULL);

	JNIEnv* env;
	vm->GetEnv((void**)&env, JNI_VERSION_1_6);

	jclass cls = env->FindClass("com/opentouchgaming/saffal/FileJNI");
	FileJNI_cls = (jclass)env->NewGlobalRef(cls);

	fopen_method  = env->GetStaticMethodID(FileJNI_cls, "fopen",  "(Ljava/lang/String;Ljava/lang/String;)I");
	mkdir_method  = env->GetStaticMethodID(FileJNI_cls, "mkdir",  "(Ljava/lang/String;)I");
	exists_method = env->GetStaticMethodID(FileJNI_cls, "exists", "(Ljava/lang/String;)I");
	delete_method = env->GetStaticMethodID(FileJNI_cls, "delete", "(Ljava/lang/String;)I");
	opendir_method= env->GetStaticMethodID(FileJNI_cls, "opendir","(Ljava/lang/String;)[Ljava/lang/String;");
	rename_method = env->GetStaticMethodID(FileJNI_cls, "rename", "(Ljava/lang/String;Ljava/lang/String;)I");
	FileCache_init();
	return JNI_VERSION_1_6;
}

extern "C" __attribute__((used)) __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_com_opentouchgaming_saffal_FileJNI_initSAFPaths(JNIEnv* env, jclass cls, jobjectArray SAFPaths, jint cacheNativeFs) {
	clearSAFPaths();

	jsize count = env->GetArrayLength(SAFPaths);
	for (jsize i = 0; i < count; i++) {
		jstring pathStr = (jstring)env->GetObjectArrayElement(SAFPaths, i);
		const char* pathC = env->GetStringUTFChars(pathStr, 0);
		addSAFPath(std::string(pathC));
		env->ReleaseStringUTFChars(pathStr, pathC);
		env->DeleteLocalRef(pathStr);
	}
}

extern "C" __attribute__((used)) __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_com_opentouchgaming_saffal_FileJNI_initSafePaths(JNIEnv* env, jclass, jobjectArray paths) {
	clearSafePaths();
	jsize count = env->GetArrayLength(paths);
	for (jsize i = 0; i < count; i++) {
		jstring pathStr = (jstring)env->GetObjectArrayElement(paths, i);
		const char* pathC = env->GetStringUTFChars(pathStr, 0);
		addSafePath(std::string(pathC));
		env->ReleaseStringUTFChars(pathStr, pathC);
		env->DeleteLocalRef(pathStr);
	}
}

extern "C" __attribute__((used)) __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_com_opentouchgaming_saffal_FileJNI_nativeSetSafEnabled(JNIEnv*, jclass, jboolean enabled) {
	g_safEnabled.store(enabled, std::memory_order_release);
}

extern "C" __attribute__((used)) __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_com_opentouchgaming_saffal_FileJNI_initPosixHooks(JNIEnv*, jclass)
{
    if (posixCallsWasHooked){
        return;
    }

	if (shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false) != 0) {
		LOGE("shadowhook_init failed: %s", shadowhook_to_errmsg(shadowhook_get_errno()));
		return;
	}

    static const char* libcName = "libc.so";
	hook_by_name(libcName, "fopen", 	 fopen,     &real_fopen);
    hook_by_name(libcName, "open",      open,      &real_open);
    hook_by_name(libcName, "rename",    rename,    &real_rename);
    hook_by_name(libcName, "chdir",     chdir,     &real_chdir);
    hook_by_name(libcName, "readdir",   readdir,   &real_readdir);
    hook_by_name(libcName, "close",     close,     &real_close);
    hook_by_name(libcName, "access",    access,    &real_access);
    hook_by_name(libcName, "mkdir",     mkdir,     &real_mkdir);
    hook_by_name(libcName, "fclose",    fclose,    &real_fclose);
    hook_by_name(libcName, "closedir",  closedir,  &real_closedir);
    hook_by_name(libcName, "scandir",   scandir,   &real_scandir);
    hook_by_name(libcName, "stat",      stat,      &real_stat);
    hook_by_name(libcName, "readdir_r", readdir_r, &real_readdir_r);
    hook_by_name(libcName, "remove",    remove,    &real_remove);
    hook_by_name(libcName, "opendir",   opendir,   &real_opendir);
    hook_by_name(libcName, "getcwd", 	 getcwd,       &real_getcwd);
	posixCallsWasHooked = true;
}

int FileJNI_fopen(const char* filename, const char* mode) {
	MUTEX_LOCK
	JNIEnv* env;
	bool attached = getEnv(&env);

	jstring jfilename = env->NewStringUTF(filename);
	jstring jmode = env->NewStringUTF(mode);
	int ret = env->CallStaticIntMethod(FileJNI_cls, fopen_method, jfilename, jmode);
	env->DeleteLocalRef(jfilename);
	env->DeleteLocalRef(jmode);

	MUTEX_UNLOCK
	return ret;
}

int FileJNI_mkdir(const char* path) {
	MUTEX_LOCK
	JNIEnv* env;
	bool attached = getEnv(&env);

	jstring jpath = env->NewStringUTF(path);
	int ret = env->CallStaticIntMethod(FileJNI_cls, mkdir_method, jpath);
	env->DeleteLocalRef(jpath);

	MUTEX_UNLOCK
	return ret;
}

int FileJNI_exists(const char* path) {
	MUTEX_LOCK
	JNIEnv* env;
	bool attached = getEnv(&env);

	jstring jpath = env->NewStringUTF(path);
	int ret = env->CallStaticIntMethod(FileJNI_cls, exists_method, jpath);
	env->DeleteLocalRef(jpath);

	MUTEX_UNLOCK
	return ret;
}

int FileJNI_delete(const char* path) {
	MUTEX_LOCK
	JNIEnv* env;
	bool attached = getEnv(&env);

	jstring jpath = env->NewStringUTF(path);
	int ret = env->CallStaticIntMethod(FileJNI_cls, delete_method, jpath);
	env->DeleteLocalRef(jpath);

	MUTEX_UNLOCK
	return ret;
}

int FileJNI_rename(const char* oldFilename, const char* newFilename) {
	MUTEX_LOCK
	JNIEnv* env;
	bool attached = getEnv(&env);

	jstring jold = env->NewStringUTF(oldFilename);
	jstring jnew = env->NewStringUTF(newFilename);
	int ret = env->CallStaticIntMethod(FileJNI_cls, rename_method, jold, jnew);
	env->DeleteLocalRef(jold);
	env->DeleteLocalRef(jnew);

	MUTEX_UNLOCK
	return ret;
}

std::vector<std::string> FileJNI_opendir(const char* path) {
	MUTEX_LOCK
	JNIEnv* env;
	bool attached = getEnv(&env);

	jstring jpath = env->NewStringUTF(path);
	jobjectArray jniItems = (jobjectArray)env->CallStaticObjectMethod(FileJNI_cls, opendir_method, jpath);
	env->DeleteLocalRef(jpath);

	std::vector<std::string> items;
	if (jniItems != nullptr) {
		jsize size = env->GetArrayLength(jniItems);
		items.reserve(size);
		for (jsize i = 0; i < size; i++) {
			jstring string = (jstring)env->GetObjectArrayElement(jniItems, i);
			const char* item = env->GetStringUTFChars(string, 0);
			items.push_back(item);
			env->ReleaseStringUTFChars(string, item);
			env->DeleteLocalRef(string);
		}
		env->DeleteLocalRef(jniItems);
	}

	MUTEX_UNLOCK
	return items;
}