#include "Utils.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <unistd.h>
#include <linux/limits.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <thread>


#define LOGI(...) ((void)0)

static std::vector<std::string> m_SAFPaths;
static std::string g_currentWorkingDirectory;

struct PathCache {
	std::string raw;
	std::string canonical;
	bool inSAF;
	bool valid = false;
};
static thread_local PathCache tls_cache;

static char* (*original_getcwd)(char*, size_t) = nullptr;

static char* safe_getcwd(char* buf, size_t size) {
	if (!original_getcwd) {
		void* libc = dlopen("libc.so", RTLD_LAZY);
		if (libc) {
			original_getcwd = (char*(*)(char*, size_t))dlsym(libc, "getcwd");
		}
		if (!original_getcwd) {
			original_getcwd = (char*(*)(char*, size_t))dlsym(RTLD_NEXT, "getcwd");
		}
	}
	if (original_getcwd) {
		return original_getcwd(buf, size);
	}
	return nullptr;
}

static void ap_getparents(char *path) {
	if (!path || !*path) return;

	char *src = path;
	char *dst = path;
	bool prevSlash = false;


	if (*src == '/') {
		*dst++ = *src++;
		prevSlash = true;
	}

	while (*src) {
		if (*src == '/') {
			if (!prevSlash) {
				*dst++ = '/';
				prevSlash = true;
			}
			++src;
		} else if (*src == '.' && (!src[1] || src[1] == '/')) {

			++src;
			if (*src == '/') ++src;
		} else if (*src == '.' && src[1] == '.' && (!src[2] || src[2] == '/')) {

			if (dst > path + 1) {
				--dst;
				while (dst > path && *(dst - 1) != '/') --dst;

				if (dst == path && *dst == '/') ++dst;
			}
			src += 2;
			if (*src == '/') ++src;
			prevSlash = (dst > path && *(dst - 1) == '/');
		} else {
			*dst++ = *src++;
			prevSlash = false;
		}
	}

	if (dst > path + 1 && *(dst - 1) == '/') {
		--dst;
	}
	*dst = '\0';
}


void clearSAFPaths()
{
	m_SAFPaths.clear();
}

void addSAFPath(std::string SAFPath)
{
	m_SAFPaths.push_back(SAFPath);
}

static bool isInSAF_internal(const std::string& path) {
	for(const auto& safPath : m_SAFPaths) {
		if(safPath.length() > 0 && path.compare(0, safPath.length(), safPath) == 0)
			return true;
	}
	return false;
}

void resolvePath(const char* raw, std::string& outCanon, bool& outSaf) {
	if (tls_cache.valid && tls_cache.raw == raw) {
		outCanon = tls_cache.canonical;
		outSaf   = tls_cache.inSAF;
		return;
	}

	std::string path(raw);
	if (!path.empty() && path[0] != '/') {
		std::string cwd = getCurrentWorkingDirectory();
		if (cwd.back() == '/')
			path = cwd + path;
		else
			path = cwd + "/" + path;
	}

	char pathC[PATH_MAX];
	strncpy(pathC, path.c_str(), PATH_MAX);
	pathC[PATH_MAX-1] = '\0';
	ap_getparents(pathC);
	outCanon = pathC;

	outSaf = isInSAF_internal(outCanon);

	tls_cache.raw = raw;
	tls_cache.canonical = outCanon;
	tls_cache.inSAF = outSaf;
	tls_cache.valid = true;
}

std::string getCanonicalPath(std::string path) {
	std::string canon;
	bool saf;
	resolvePath(path.c_str(), canon, saf);
	return canon;
}

bool isInSAF(std::string path) {
	std::string canon;
	bool saf;
	resolvePath(path.c_str(), canon, saf);
	return saf;
}

std::string getCurrentWorkingDirectory() {
	if (g_currentWorkingDirectory.empty()) {
		char* cstr = safe_getcwd(nullptr, 0);
		if (cstr) {
			g_currentWorkingDirectory = cstr;
			free(cstr);
		} else {
			g_currentWorkingDirectory = "/";
		}
	}
	return g_currentWorkingDirectory;
}

void setCurrentWorkingDirectory(const std::string &cwd) {
	g_currentWorkingDirectory = cwd;
}