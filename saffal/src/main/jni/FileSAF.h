#ifndef FILESAF_H
#define FILESAF_H
#include <cstdio>
#include <sys/types.h>
#include <sys/stat.h>
#include <dirent.h>
#include <cstddef>

#ifdef __cplusplus
extern "C" {
#endif

FILE*   fopen(const char* filename, const char* mode);
int     fclose(FILE* file);
int     close(int fd);
int     open(const char* path, int flags, ...);
int     __open_2(const char* path, int oflag, mode_t modes);
int     stat(const char* path, struct stat* statbuf);
int     access(const char* pathname, int mode);
DIR*    opendir(const char* name);
struct dirent* readdir(DIR* dirp);
int     closedir(DIR* dirp);
int     scandir(const char* dirp, struct dirent*** namelist,
                int (*filter)(const struct dirent*),
                int (*compar)(const struct dirent**, const struct dirent**));
int     remove(const char* path);
int     rename(const char* old_filename, const char* new_filename);
int     mkdir(const char* path, mode_t mode);
int     chdir(const char* path);
char* _Nullable getcwd(char* const buf, size_t size);

#ifdef __cplusplus
}
#endif

#endif // FILESAF_H