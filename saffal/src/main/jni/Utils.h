#ifndef UTILS_H
#define UTILS_H

#include <string>
#include "utility"

void clearSAFPaths();
void addSAFPath(std::string safPath);

std::string getCanonicalPath(std::string path);
bool isInSAF(std::string path);

std::string getCurrentWorkingDirectory();
void setCurrentWorkingDirectory(const std::string &cwd);
void clearSafePaths();
void addSafePath(const std::string& path);
bool isInSafePath(const std::string& path);
#endif