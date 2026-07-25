#include "Utils.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <unistd.h>
#include <linux/limits.h>
#include <android/log.h>
#include <vector>

#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO,"Utils NDK", __VA_ARGS__))

static std::vector<std::string> m_SAFPaths;
static std::string g_currentWorkingDirectory;

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

#define IS_SLASH(s) (s == '/')
static void ap_getparents(char *name)
{
	char *next;
	int l, w, first_dot;

	for(next = name; *next && (*next != '.'); next++) {}
	l = w = first_dot = next - name;

	while(name[l] != '\0')
	{
		if(name[l] == '.' && IS_SLASH(name[l + 1])
		   && (l == 0 || IS_SLASH(name[l - 1])))
			l += 2;
		else
			name[w++] = name[l++];
	}

	if(w == 1 && name[0] == '.')
		w--;
	else if(w > 1 && name[w - 1] == '.' && IS_SLASH(name[w - 2]))
		w--;
	name[w] = '\0';

	l = first_dot;
	while(name[l] != '\0')
	{
		if(name[l] == '.' && name[l + 1] == '.' && IS_SLASH(name[l + 2])
		   && (l == 0 || IS_SLASH(name[l - 1])))
		{
			int m = l + 3, n;
			l = l - 2;
			if(l >= 0)
			{
				while(l >= 0 && !IS_SLASH(name[l]))
					l--;
				l++;
			}
			else l = 0;
			n = l;
			while((name[n] = name[m])) (++n, ++m);
		}
		else ++l;
	}

	if(l == 2 && name[0] == '.' && name[1] == '.')
		name[0] = '\0';
	else if(l > 2 && name[l - 1] == '.' && name[l - 2] == '.'
	        && IS_SLASH(name[l - 3]))
	{
		l = l - 4;
		if(l >= 0)
		{
			while(l >= 0 && !IS_SLASH(name[l]))
				l--;
			l++;
		}
		else l = 0;
		name[l] = '\0';
	}
}

void clearSAFPaths()
{
	m_SAFPaths.clear();
}

void addSAFPath(std::string SAFPath)
{
	m_SAFPaths.push_back(SAFPath);
}

std::string getCanonicalPath(std::string path)
{
	if (!path.empty() && path[0] != '/')
	{
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
	return std::string(pathC);
}

bool isInSAF(std::string path)
{
	for(const auto& safPath : m_SAFPaths)
	{
		if(safPath.length() > 0 && path.rfind(safPath, 0) == 0)
			return true;
	}
	return false;
}

std::string getCurrentWorkingDirectory()
{
	if (g_currentWorkingDirectory.empty())
	{
		char* cstr = safe_getcwd(nullptr, 0);
		if (cstr)
		{
			g_currentWorkingDirectory = cstr;
			free(cstr);
		}
		else
		{
			g_currentWorkingDirectory = "/";
		}
	}
	return g_currentWorkingDirectory;
}

void setCurrentWorkingDirectory(const std::string &cwd)
{
	g_currentWorkingDirectory = cwd;
}