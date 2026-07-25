#include "FileCache.h"
#include "FileJNI.h"

#include <sys/types.h>
#include <unistd.h>
#include <pthread.h>

#include <string>
#include <unordered_map>
#include <unordered_set>
#include <list>
#include <utility>

#include <android/log.h>

static const size_t MAXIMUM_CACHED_FILES = 256;

#define LOGI(...) ((void)0)
#define LOGIALWAYS(...) __android_log_print(ANDROID_LOG_INFO, "FileCache NDK", __VA_ARGS__)

static pthread_mutex_t lock;
#define MUTEX_LOCK   pthread_mutex_lock(&lock)
#define MUTEX_UNLOCK pthread_mutex_unlock(&lock)

extern "C" void* loadRealFunc(const char * name);
static std::unordered_map<std::string, int> cacheFree;
static std::unordered_map<int, std::string> cacheActive;
static std::unordered_set<std::string> userFileKeys;
static std::list<std::string> lruList;
static std::unordered_map<std::string, std::list<std::string>::iterator> lruMap;

void FileCache_init() {
	pthread_mutex_init(&lock, nullptr);
}

extern "C" void clearUserFilesFromCache(int mutextLock) {
	if (mutextLock) MUTEX_LOCK;

	static int (*close_real)(int) = nullptr;
	if (close_real == nullptr)
		close_real = (int (*)(int))loadRealFunc("close");
	auto it = userFileKeys.begin();
	while (it != userFileKeys.end()) {
		auto cacheIt = cacheFree.find(*it);
		if (cacheIt != cacheFree.end()) {
			int fd = cacheIt->second;
			close_real(fd);

			auto lruIt = lruMap.find(*it);
			if (lruIt != lruMap.end()) {
				lruList.erase(lruIt->second);
				lruMap.erase(lruIt);
			}
			cacheFree.erase(cacheIt);
		}
		it = userFileKeys.erase(it);
	}

	if (mutextLock) MUTEX_UNLOCK;
}

int FileCache_getFd(const char * filename, const char * mode,
                    int (*openFunc)(const char * filename, const char * mode)) {
	MUTEX_LOCK;
	std::string fileTag = std::string(filename) + " - " + mode;
	int fd = 0;
	if (strchr(mode, 'w') || strchr(mode, 'a')) {
		clearUserFilesFromCache(0);
		fd = openFunc(filename, mode);
		MUTEX_UNLOCK;
		return fd;
	}
	auto freeIt = cacheFree.find(fileTag);
	if (freeIt == cacheFree.end()) {
		fd = openFunc(filename, mode);
		if (fd <= 0) {
			MUTEX_UNLOCK;
			return fd;
		}
	} else {
		fd = freeIt->second;
		cacheFree.erase(freeIt);
		auto lruIt = lruMap.find(fileTag);
		if (lruIt != lruMap.end()) {
			lruList.erase(lruIt->second);
			lruMap.erase(lruIt);
		}
		lseek(fd, 0, SEEK_SET);
	}
	cacheActive[fd] = fileTag;
	MUTEX_UNLOCK;
	return fd;
}

static void closeFd(int fd) {
	MUTEX_LOCK;
	auto activeIt = cacheActive.find(fd);
	if (activeIt == cacheActive.end()) {
		MUTEX_UNLOCK;
		return;
	}
	const std::string & key = activeIt->second;
	if (cacheFree.size() >= MAXIMUM_CACHED_FILES) {
		if (!lruList.empty()) {
			const std::string & oldest = lruList.front();
			auto freeIt = cacheFree.find(oldest);
			if (freeIt != cacheFree.end()) {
				static int (*close_real)(int) = nullptr;
				if (close_real == nullptr)
					close_real = (int (*)(int))loadRealFunc("close");
				close_real(freeIt->second);
				cacheFree.erase(freeIt);
				userFileKeys.erase(oldest);
			}
			lruMap.erase(oldest);
			lruList.pop_front();
		}
	}
	int fdCopy = dup(fd);
	if (fdCopy == -1) {
		MUTEX_UNLOCK;
		return;
	}
	cacheFree[key] = fdCopy;
	if (key.find("user_files") != std::string::npos) {
		userFileKeys.insert(key);
	}

	lruList.push_back(key);
	auto it = lruList.end(); --it;
	lruMap[key] = it;
	cacheActive.erase(activeIt);
	MUTEX_UNLOCK;
}

int FileCache_closeFd(int fd) {
	closeFd(fd);
	return 0;
}

int FileCache_closeFile(FILE * file) {
	if (!file) return 0;
	int fd = fileno(file);
	closeFd(fd);
	return 0;
}