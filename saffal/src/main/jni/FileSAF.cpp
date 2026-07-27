#include "FileSAF.h"
#include "FileJNI.h"
#include "FileCache.h"
#include "Utils.h"

#include <cstdio>
#include <dlfcn.h>
#include <dirent.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>
#include <libgen.h>
#include <cerrno>
#include <thread>

#include <string>
#include <map>
#include <vector>
#include <set>
#include <atomic>
#include <cstring>
#include <android/log.h>
#include <asm-generic/fcntl.h>

#define LOGI(...) ((void)0)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FileSAF NDK", __VA_ARGS__)

#define O_RDONLY  00
#define O_WRONLY  01
#define O_RDWR    02

std::atomic<bool> g_safEnabled{false};
FILE *(*real_fopen)(const char *, const char *) = nullptr;
int (*real_open)(const char *, int, ...) = nullptr;
int (*real_fclose)(FILE *) = nullptr;
int (*real_close)(int) = nullptr;
int (*real_mkdir)(const char *, mode_t) = nullptr;
int (*real_stat)(const char *, struct stat *) = nullptr;
int (*real_access)(const char *, int) = nullptr;
int (*real_remove)(const char *) = nullptr;
struct dirent *(*real_readdir)(DIR *) = nullptr;
DIR *(*real_opendir)(const char *) = nullptr;
int (*real_readdir_r)(DIR *,struct dirent *,
                      struct dirent **) = nullptr;
int (*real_closedir)(DIR *) = nullptr;
int (*real_scandir)(const char *,
                    struct dirent ***,
                    int (*)(const struct dirent *),
                    int (*)(const struct dirent **,
                            const struct dirent **)) = nullptr;
int (*real_rename)(const char *,
                   const char *) = nullptr;
int (*real_chdir)(const char *) = nullptr;
char *(*real_getcwd)(char* const buf, size_t size) = nullptr;

extern "C" void clearUserFilesFromCache(int lock);
extern bool isInSafePath(const std::string& path);

extern "C" {
struct PathCache {
	std::string raw;
	std::string canonical;
	bool inSAF;
	bool valid = false;
};
static thread_local PathCache tls_cache;

static inline void resolve_path(const char *raw, std::string &canon, bool &saf) {
	if (tls_cache.valid && tls_cache.raw == raw) {
		canon = tls_cache.canonical;
		saf   = tls_cache.inSAF;
		return;
	}
	canon = getCanonicalPath(std::string(raw));

	if (!g_safEnabled.load(std::memory_order_acquire) || isInSafePath(canon)) {
		saf = false;
	} else {
		saf = isInSAF(canon);
	}

	tls_cache.raw       = raw;
	tls_cache.canonical = canon;
	tls_cache.inSAF     = saf;
	tls_cache.valid     = true;
}

void *loadRealFunc(const char *name) {
	static void *libc = NULL;
	if (libc == NULL) {
		libc = dlopen("libc.so", 0);
		if (!libc) LOGE("FATAL: libc not loaded");
	}
	void *func = libc ? dlsym(libc, name) : dlsym(RTLD_NEXT, name);
	if (!func) LOGE("FATAL: %s not found", name);
	return func;
}

int open(const char* path, int flags, ...)
{
    std::string canonical;
    bool saf;
    resolve_path(path, canonical, saf);
    if (saf)
    {
        const char* mode = (flags & (O_WRONLY | O_RDWR)) ? "w" : "r";
        int fd = FileCache_getFd(
                canonical.c_str(),
                mode,
                FileJNI_fopen
        );
        return fd >= 0 ? fd : -1;
    }
    mode_t modes = 0;
    if (flags & O_CREAT)
    {
        va_list args;
        va_start(args, flags);
        modes = va_arg(args, mode_t);
        va_end(args);
        return real_open(path, flags, modes);
    }
    return real_open(path, flags);
}


int __open_2(const char *path, int oflag, mode_t modes) {
	return open(path, oflag, modes);
}

FILE *fopen(const char *filename, const char *mode) {
	if (!filename || !mode) return NULL;

	std::string canonical; bool saf;
	resolve_path(filename, canonical, saf);

	if (saf) {
		int fd = FileCache_getFd(canonical.c_str(), mode, FileJNI_fopen);
		if (fd > 0) {
			FILE *f = fdopen(fd, mode);
			return f;
		}
		return NULL;
	} else {
		return real_fopen(filename, mode);
	}
}

int fclose(FILE *file) {
	FileCache_closeFile(file);
	return real_fclose(file);
}

int close(int fd) {
	FileCache_closeFd(fd);
	return real_close(fd);
}

int mkdir(const char *path, mode_t mode) {
	std::string canonical; bool saf;
	resolve_path(path, canonical, saf);
	int status = FileJNI_mkdir(canonical.c_str());
	if (status == 0) return 0;
	if (status == 1) return -1;
	return real_mkdir(path, mode);
}

int stat(const char *path, struct stat *statbuf) {
	std::string canonical; bool saf;
	resolve_path(path, canonical, saf);

	if (saf) {
		int fd = FileCache_getFd(canonical.c_str(), "r", FileJNI_fopen);
		if (fd > 0) {
			int ret = fstat(fd, statbuf);
			close(fd);
			return ret;
		}
		return -1;
	} else {
		return real_stat(path, statbuf);
	}
}

int remove(const char *path) {
	std::string canonical; bool saf;
	resolve_path(path, canonical, saf);
	if (saf) {
		clearUserFilesFromCache(1);
		return FileJNI_delete(canonical.c_str());
	} else {
		return real_remove(path);
	}
}

int access(const char *pathname, int mode) {
	std::string canonical; bool saf;
	resolve_path(pathname, canonical, saf);
	if (saf) {
		return FileJNI_exists(canonical.c_str()) ? 0 : -1;
	} else {
		return real_access(pathname, mode);
	}
}

class DIR_SAF {
public:
	int position = 0;
	std::vector<struct dirent> items;
};
static std::set<DIR_SAF *> openDIRS;

DIR *opendir(const char *name) {
	std::string canonical; bool saf;
	resolve_path(name, canonical, saf);

	if (saf) {
		std::vector<std::string> items = FileJNI_opendir(canonical.c_str());
		if (!items.empty()) {
			DIR_SAF *d = new DIR_SAF();
			d->items.reserve(items.size());
			for (auto & item : items) {
				struct dirent de;
				memset(&de, 0, sizeof(de));
				std::string type  = item.substr(0, 1);
				std::string fname = item.substr(1);
				if (type == "F") de.d_type = DT_REG;
				else if (type == "D") de.d_type = DT_DIR;
				else de.d_type = DT_UNKNOWN;
				strncpy(de.d_name, fname.c_str(), sizeof(de.d_name)-1);
				d->items.push_back(de);
			}
			openDIRS.insert(d);
			return (DIR *)d;
		}
		return NULL;
	} else {
		return real_opendir(name);
	}
}

struct dirent *readdir(DIR *dirp) {
	DIR_SAF *s = (DIR_SAF *)dirp;
	if (openDIRS.find(s) != openDIRS.end()) {
		if (s->position < s->items.size())
			return &s->items[s->position++];
		return NULL;
	} else {
		return real_readdir(dirp);
	}
}

int readdir_r(DIR *dirp, struct dirent *entry, struct dirent **result) {
	DIR_SAF *s = (DIR_SAF *)dirp;
	if (openDIRS.find(s) != openDIRS.end()) {
		if (s->position < s->items.size()) {
			*entry = s->items[s->position++];
			*result = entry;
			return 0;
		} else {
			*result = nullptr;
			return 0;
		}
	} else {
		return real_readdir_r(dirp, entry, result);
	}
}

int closedir(DIR *dirp) {
	DIR_SAF *s = (DIR_SAF *)dirp;
	auto it = openDIRS.find(s);
	if (it != openDIRS.end()) {
		openDIRS.erase(it);
		delete s;
		return 0;
	} else {
		return real_closedir(dirp);
	}
}

int scandir(const char *dirp, struct dirent ***namelist,
            int (*filter)(const struct dirent *),
            int (*compar)(const struct dirent **, const struct dirent **)) {
	std::string canonical; bool saf;
	resolve_path(dirp, canonical, saf);
	if (saf) {
		DIR_SAF *d = (DIR_SAF *)opendir(canonical.c_str());
		if (!d) return 0;
		int cnt = 0;
		*namelist = (dirent **)malloc(sizeof(dirent *) * d->items.size());
		for (size_t i = 0; i < d->items.size(); ++i) {
			if (!filter || filter(&d->items[i]) != 0) {
				(*namelist)[cnt] = (dirent *)malloc(sizeof(dirent));
				memcpy((*namelist)[cnt], &d->items[i], sizeof(dirent));
				cnt++;
			}
		}
		closedir((DIR *)d);
		return cnt;
	} else {
		return real_scandir(dirp, namelist, filter, compar);
	}
}

int rename(const char *old, const char *new_) {
	std::string oldCanon, newCanon; bool oldSaf, newSaf;
	resolve_path(old,  oldCanon,  oldSaf);
	resolve_path(new_, newCanon, newSaf);
	if (oldSaf && newSaf) {
		clearUserFilesFromCache(1);
		return FileJNI_rename(oldCanon.c_str(), newCanon.c_str());
	} else {
		return real_rename(old, new_);
	}
}

int chdir(const char *path) {
	std::string canonical; bool saf;
	resolve_path(path, canonical, saf);
	int ret = real_chdir(canonical.c_str());
	if (ret == 0) setCurrentWorkingDirectory(canonical);
	return ret;
}

char *getcwd(char *buf, size_t size) {
	std::string cwd = getCurrentWorkingDirectory();
	if (!buf) {
		if (size == 0) size = cwd.length() + 1;
		buf = (char *)malloc(size);
		if (!buf) return NULL;
	} else if (cwd.length() + 1 > size) {
		errno = ERANGE;
		return NULL;
	}
	memcpy(buf, cwd.c_str(), cwd.length() + 1);
	return buf;
}
}