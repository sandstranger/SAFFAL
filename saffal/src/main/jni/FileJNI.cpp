#include "FileJNI.h"
#include "FileCache.h"
#include "Utils.h"

#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <vector>

static pthread_mutex_t lock;


#define LOGI(...) ((void)0)
#define LOGW(...) ((void)0)

#if 0
#define MUTEX_LOCK   pthread_mutex_lock(&lock);
#define MUTEX_UNLOCK pthread_mutex_unlock(&lock);
#else
#define MUTEX_LOCK
#define MUTEX_UNLOCK
#endif

static JavaVM* m_jvm = nullptr;
static JNIEnv* firstEnv = nullptr;

extern bool cacheInvalidPaths;

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

extern "C" __attribute__((visibility("default"))) jint JNI_OnLoad(JavaVM* vm, void* reserved) {
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

extern "C" JNIEXPORT void JNICALL
Java_com_opentouchgaming_saffal_FileJNI_initSAFPaths(JNIEnv* env, jclass cls, jobjectArray SAFPaths, jint cacheNativeFs) {
	cacheInvalidPaths = (cacheNativeFs != 0);
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

extern "C" JNIEXPORT void JNICALL
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