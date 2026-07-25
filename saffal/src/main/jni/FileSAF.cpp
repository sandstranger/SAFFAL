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

#include <android/log.h>

#define LOGI(...) ((void)0)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FileSAF NDK", __VA_ARGS__)

#define O_RDONLY  00
#define O_WRONLY  01
#define O_RDWR    02

static std::map<std::string, int> invalidPaths;
bool cacheInvalidPaths = true;

extern "C" void clearUserFilesFromCache(int lock);

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
	saf   = isInSAF(canon);
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

bool checkPathExistsCache(const char *path) {
	bool exists = false;
	const char *parent = dirname(path);
	std::string parentString = parent;
	if (parentString.length() > 2) {
		if (invalidPaths.find(parentString) == invalidPaths.end()) {
			static int (*stat_real)(const char *, struct stat *) = NULL;
			if (!stat_real) stat_real = (int (*)(const char *, struct stat *)) loadRealFunc("stat");
			struct stat st;
			if (!stat_real(parentString.c_str(), &st)) {
				invalidPaths[parentString] = 1;
				exists = true;
			} else {
				invalidPaths[parentString] = 0;
			}
		} else {
			exists = (invalidPaths[parentString] == 1);
		}
	} else {
		exists = true;
	}
	return exists;
}


int open(const char *path, int oflag, mode_t modes) {
	std::string canonical; bool saf;
	resolve_path(path, canonical, saf);

	if (saf) {
		const char *mode = (oflag & (O_WRONLY | O_RDWR)) ? "w" : "r";
		int fd = FileCache_getFd(canonical.c_str(), mode, FileJNI_fopen);
		return (fd > 0) ? fd : -1;
	} else {
		static int (*real)(const char *, int, mode_t) = NULL;
		if (!real) real = (int (*)(const char *, int, mode_t)) loadRealFunc("open");
		return real(path, oflag, modes);
	}
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
		static FILE *(*real)(const char *, const char *) = NULL;
		if (!real) real = (FILE *(*)(const char *, const char *)) loadRealFunc("fopen");
		return real(filename, mode);
	}
}

int fclose(FILE *file) {
	FileCache_closeFile(file);
	static int (*real)(FILE *) = NULL;
	if (!real) real = (int (*)(FILE *)) loadRealFunc("fclose");
	return real(file);
}

int close(int fd) {
	FileCache_closeFd(fd);
	static int (*real)(int) = NULL;
	if (!real) real = (int (*)(int)) loadRealFunc("close");
	return real(fd);
}

int mkdir(const char *path, mode_t mode) {
	if (cacheInvalidPaths) invalidPaths.clear();
	std::string canonical; bool saf;
	resolve_path(path, canonical, saf);
	int status = FileJNI_mkdir(canonical.c_str());
	if (status == 0) return 0;
	if (status == 1) return -1;
	static int (*real)(const char *, mode_t) = NULL;
	if (!real) real = (int (*)(const char *, mode_t)) loadRealFunc("mkdir");
	return real(path, mode);
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
		static int (*real)(const char *, struct stat *) = NULL;
		if (!real) real = (int (*)(const char *, struct stat *)) loadRealFunc("stat");
		if (cacheInvalidPaths && !checkPathExistsCache(path))
			return -1;
		return real(path, statbuf);
	}
}

int remove(const char *path) {
	std::string canonical; bool saf;
	resolve_path(path, canonical, saf);
	if (saf) {
		clearUserFilesFromCache(1);
		return FileJNI_delete(canonical.c_str());
	} else {
		static int (*real)(const char *) = NULL;
		if (!real) real = (int (*)(const char *)) loadRealFunc("remove");
		return real(path);
	}
}

int access(const char *pathname, int mode) {
	std::string canonical; bool saf;
	resolve_path(pathname, canonical, saf);
	if (saf) {
		return FileJNI_exists(canonical.c_str()) ? 0 : -1;
	} else {
		static int (*real)(const char *, int) = NULL;
		if (!real) real = (int (*)(const char *, int)) loadRealFunc("access");
		return real(pathname, mode);
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
		static DIR *(*real)(const char *) = NULL;
		if (!real) real = (DIR *(*)(const char *)) loadRealFunc("opendir");
		return real(name);
	}
}

struct dirent *readdir(DIR *dirp) {
	DIR_SAF *s = (DIR_SAF *)dirp;
	if (openDIRS.find(s) != openDIRS.end()) {
		if (s->position < s->items.size())
			return &s->items[s->position++];
		return NULL;
	} else {
		static struct dirent *(*real)(DIR *) = NULL;
		if (!real) real = (struct dirent *(*)(DIR *)) loadRealFunc("readdir");
		return real(dirp);
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
		static int (*real)(DIR *, struct dirent *, struct dirent **) = NULL;
		if (!real) real = (int (*)(DIR *, struct dirent *, struct dirent **)) loadRealFunc("readdir_r");
		return real(dirp, entry, result);
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
		static int (*real)(DIR *) = NULL;
		if (!real) real = (int (*)(DIR *)) loadRealFunc("closedir");
		return real(dirp);
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
		static int (*real)(const char *, struct dirent ***,
		                   int (*)(const struct dirent *),
		                   int (*)(const struct dirent **, const struct dirent **)) = NULL;
		if (!real)
			real = (int (*)(const char *, struct dirent ***,
			                int (*)(const struct dirent *),
			                int (*)(const struct dirent **, const struct dirent **))) loadRealFunc("scandir");
		return real(dirp, namelist, filter, compar);
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
		static int (*real)(const char *, const char *) = NULL;
		if (!real) real = (int (*)(const char *, const char *)) loadRealFunc("rename");
		return real(old, new_);
	}
}

int chdir(const char *path) {
	std::string canonical; bool saf;
	resolve_path(path, canonical, saf);
	static int (*real)(const char *) = NULL;
	if (!real) real = (int (*)(const char *)) loadRealFunc("chdir");
	int ret = real(canonical.c_str());
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
