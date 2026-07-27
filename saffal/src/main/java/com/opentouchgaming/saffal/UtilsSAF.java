package com.opentouchgaming.saffal;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UtilsSAF {
    private static final String TAG = "UtilsSAF";
    private static final String IS_SAF_ENABLED_SHARED_PREFS_KEY = "enable_saf";
    private static Context appContext;
    private static int cacheNativeFs = 0;
    private static final List<TreeRoot> treeRoots = new ArrayList<>();
    private static final Map<String, Boolean> safCache = new HashMap<>();
    private static final Map<String, Boolean> rootPrefixCache = new HashMap<>();
    private static final String[] jniLibs = new String[]{"shadowhook", "saffal"};
    private static volatile boolean safEnabled = false;

    private static void invalidateCaches() {
        safCache.clear();
        rootPrefixCache.clear();
    }

    public static void setContext(@NonNull Context ctx) {
        appContext = ctx.getApplicationContext();
        safEnabled = LoadSafEnabledStateFromSharesPrefs();
        for (var jniLib : jniLibs) {
            System.loadLibrary(jniLib);
        }
        var cachePath = appContext.getCacheDir().getAbsolutePath();
        var filesPath = appContext.getFilesDir().getAbsolutePath();
        var externalFilesPath = appContext.getExternalFilesDir(null).getAbsolutePath();
        FileJNI.initSafePaths(new String[]{cachePath, filesPath, externalFilesPath});
        FileJNI.nativeSetSafEnabled(safEnabled);
        if (safEnabled) {
            FileJNI.initPosixHooks();
        }
        else {
            clearTreeRoots();
        }
    }

    public static Context getContext() {
        return appContext;
    }

    public static ContentResolver getContentResolver() {
        if (appContext == null)
            throw new IllegalStateException("UtilsSAF.setContext() must be called first");
        return appContext.getContentResolver();
    }

    @SuppressLint("ApplySharedPref")
    public static void setSafEnabled(boolean enabled) {
        safEnabled = enabled;
        FileJNI.nativeSetSafEnabled(enabled);
        if (!enabled) {
            clearTreeRoots();
        }
        SharedPreferences prefs = appContext.getSharedPreferences("utilsSAF", Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit();
        edit.clear();
        edit.putBoolean(IS_SAF_ENABLED_SHARED_PREFS_KEY, enabled);
        edit.commit();
    }

    public static boolean isSafEnabled() {
        return safEnabled;
    }

    public static boolean addTreeRootFromUri(@NonNull Uri treeUri) {
        if (appContext == null) throw new IllegalStateException("setContext not called");

        getContentResolver().takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        if (documentId == null) {
            Log.e(TAG, "Cannot extract document ID from URI: " + treeUri);
            return false;
        }

        String rootPath = guessRootPath(documentId);
        if (rootPath == null)
            rootPath = "/saf/tree_" + Math.abs(treeUri.hashCode());

        TreeRoot root = new TreeRoot(treeUri, rootPath, documentId);
        addTreeRoot(root);
        return true;
    }

    private static boolean LoadSafEnabledStateFromSharesPrefs(){
        SharedPreferences prefs = appContext.getSharedPreferences("utilsSAF", Context.MODE_PRIVATE);
        return prefs.getBoolean(IS_SAF_ENABLED_SHARED_PREFS_KEY, false);
    }

    @Nullable
    private static String guessRootPath(String documentId) {
        if (TextUtils.isEmpty(documentId)) return null;

        int colonIndex = documentId.indexOf(':');
        String volume, path;
        if (colonIndex < 0) {
            volume = documentId;
            path = "";
        } else {
            volume = documentId.substring(0, colonIndex);
            path = documentId.substring(colonIndex + 1);
        }

        if (volume.equalsIgnoreCase("primary")) {
            String base = Environment.getExternalStorageDirectory().getAbsolutePath();
            if (path.isEmpty()) return base;
            if (path.charAt(0) == '/') path = path.substring(1);
            return base + "/" + path;
        }

        if (volume.length() == 9 && volume.charAt(4) == '-') {
            String base = "/storage/" + volume;
            if (path.isEmpty()) return base;
            if (path.charAt(0) == '/') path = path.substring(1);
            return base + "/" + path;
        }

        return null;
    }

    public static void addTreeRoot(@NonNull TreeRoot root) {
        DocumentNode documentRoot = new DocumentNode();
        documentRoot.name = "root";
        documentRoot.isDirectory = true;
        documentRoot.documentId = root.rootDocumentId;
        documentRoot.treeUri = root.uri;
        root.documentRoot = documentRoot;

        treeRoots.add(root);
        invalidateCaches();
        rebuildNativePaths();
    }

    public static void removeTreeRoot(@NonNull String rootPath) {
        treeRoots.removeIf(r -> r.rootPath.equals(rootPath));
        invalidateCaches();
        rebuildNativePaths();
    }

    public static void clearTreeRoots() {
        treeRoots.clear();
        invalidateCaches();
        rebuildNativePaths();
    }

    private static void rebuildNativePaths() {
        String[] paths = new String[treeRoots.size()];
        for (int i = 0; i < treeRoots.size(); i++) {
            paths[i] = treeRoots.get(i).rootPath;
        }
        FileJNI.initSAFPaths(paths, cacheNativeFs);
    }

    public static List<TreeRoot> getTreeRoots() {
        return treeRoots;
    }

    @Nullable
    public static TreeRoot getTreeRoot() {
        return treeRoots.isEmpty() ? null : treeRoots.get(0);
    }

    @Nullable
    public static TreeRoot getTreeRootForPath(String path) {
        for (TreeRoot root : treeRoots) {
            if (path.startsWith(root.rootPath))
                return root;
        }
        return null;
    }

    public static boolean isInSAFRoot(String path) {
        if (treeRoots.isEmpty()) return false;

        Boolean cached = safCache.get(path);
        if (cached != null) return cached;

        for (TreeRoot root : treeRoots) {
            if (path.startsWith(root.rootPath)) {
                safCache.put(path, Boolean.TRUE);
                return true;
            }
        }

        safCache.put(path, Boolean.FALSE);
        return false;
    }

    public static boolean isRootOfSAFRoot(String path) {
        if (treeRoots.isEmpty()) return false;

        Boolean cached = rootPrefixCache.get(path);
        if (cached != null) return cached;

        String[] inputParts = path.split("/");
        for (TreeRoot root : treeRoots) {
            String[] rootParts = root.rootPath.split("/");
            if (inputParts.length > rootParts.length) continue;
            boolean match = true;
            for (int i = 0; i < inputParts.length; i++) {
                if (!inputParts[i].equals(rootParts[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                rootPrefixCache.put(path, Boolean.TRUE);
                return true;
            }
        }

        rootPrefixCache.put(path, Boolean.FALSE);
        return false;
    }

    @Nullable
    public static DocumentNode findDocumentNode(String fullPath) {
        TreeRoot root = getTreeRootForPath(fullPath);
        if (root == null) return null;
        String relativePath = getDocumentPath(fullPath, root);
        return DocumentNode.findDocumentNode(root.documentRoot, relativePath);
    }

    public static void saveTreeRoots() {
        if (appContext == null) return;
        SharedPreferences prefs = appContext.getSharedPreferences("utilsSAF", Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit();
        edit.clear();
        int count = 0;
        for (TreeRoot root : treeRoots) {
            if (root.uri != null && root.rootPath != null && root.rootDocumentId != null) {
                edit.putString("uri_" + count, root.uri.toString());
                edit.putString("rootPath_" + count, root.rootPath);
                edit.putString("rootDocumentId_" + count, root.rootDocumentId);
                count++;
            }
        }
        edit.putInt("count", count);
        edit.apply();
    }

    public static boolean loadTreeRoots() {
        if (appContext == null || !safEnabled) return false;
        SharedPreferences prefs = appContext.getSharedPreferences("utilsSAF", Context.MODE_PRIVATE);
        int count = prefs.getInt("count", 0);
        if (count > 0) {
            treeRoots.clear();
            invalidateCaches();
            for (int i = 0; i < count; i++) {
                String uriStr = prefs.getString("uri_" + i, null);
                String rootPath = prefs.getString("rootPath_" + i, null);
                String rootDocId = prefs.getString("rootDocumentId_" + i, null);
                if (uriStr != null && rootPath != null && rootDocId != null) {
                    Uri uri = Uri.parse(uriStr);
                    addTreeRoot(new TreeRoot(uri, rootPath, rootDocId));
                }
            }
            return !treeRoots.isEmpty();
        } else {
            String uriStr = prefs.getString("uri", null);
            if (uriStr != null) {
                String rootPath = prefs.getString("rootPath", null);
                String rootDocId = prefs.getString("rootDocumentId", null);
                if (rootPath != null && rootDocId != null) {
                    addTreeRoot(new TreeRoot(Uri.parse(uriStr), rootPath, rootDocId));
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    public static String getRealPathFromUri(@NonNull Uri uri) {
        if (appContext == null) return null;

        try {
            String docId = DocumentsContract.getDocumentId(uri);
            if (docId != null) {
                int colonIdx = docId.indexOf(':');
                if (colonIdx > 0) {
                    String vol = docId.substring(0, colonIdx);
                    String relative = docId.substring(colonIdx + 1);
                    if (relative.startsWith("/")) relative = relative.substring(1);

                    if (vol.equalsIgnoreCase("primary"))
                        return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + relative;
                    if (vol.length() == 9 && vol.charAt(4) == '-')
                        return "/storage/" + vol + "/" + relative;
                }
            }
        } catch (Exception ignored) {
        }

        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd != null) {
                String rawPath = getFdPath(pfd.getFd());
                pfd.close();
                if (!TextUtils.isEmpty(rawPath)) {
                    String normalized = normalizeInternalPath(rawPath);
                    return normalized != null ? normalized : rawPath;
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static String normalizeInternalPath(String path) {
        if (path.startsWith("/mnt/user/0/emulated/"))
            return "/storage/emulated/" + path.substring("/mnt/user/0/emulated/".length());
        if (path.startsWith("/mnt/media_rw/")) {
            String rest = path.substring("/mnt/media_rw/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                String vol = rest.substring(0, slash);
                String after = rest.substring(slash);
                return "/storage/" + vol + after;
            } else {
                return "/storage/" + rest;
            }
        }
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    static ParcelFileDescriptor getParcelDescriptor(String documentId, Uri treeUri, boolean write) throws IOException {
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        return getContentResolver().openFileDescriptor(docUri, write ? "rwt" : "r");
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    static boolean renameDocument(String documentId, Uri treeUri, String newName) throws IOException {
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        DocumentsContract.renameDocument(getContentResolver(), docUri, newName);
        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    static boolean deleteDocument(String documentId, Uri treeUri) throws IOException {
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        return DocumentsContract.deleteDocument(getContentResolver(), docUri);
    }

    static InputStream getInputStream(DocumentFile docFile) throws FileNotFoundException {
        return getContentResolver().openInputStream(docFile.getUri());
    }

    @Nullable
    public static String getDocumentPath(String fullPath) {
        TreeRoot root = getTreeRootForPath(fullPath);
        if (root == null) return null;
        return getDocumentPath(fullPath, root);
    }

    private static String getDocumentPath(String fullPath, TreeRoot root) {
        if (fullPath.length() > root.rootPath.length())
            return fullPath.substring(root.rootPath.length() + 1);
        return "";
    }

    public static String[] getParts(String fullPath) {
        String childPath = getDocumentPath(fullPath);
        if (childPath == null || childPath.isEmpty()) return new String[0];
        return childPath.split("/", -1);
    }

    @Nullable
    public static String getFdPath(int fd) {
        try {
            final File procfsFdFile = new File("/proc/self/fd/" + fd);
            String resolved;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                resolved = android.system.Os.readlink(procfsFdFile.getAbsolutePath());
            } else {
                resolved = procfsFdFile.getCanonicalPath();
            }
            if (TextUtils.isEmpty(resolved) || resolved.charAt(0) != '/' ||
                    resolved.startsWith("/proc/") || resolved.startsWith("/fd/"))
                return null;
            return resolved;
        } catch (Exception e) {
            return null;
        }
    }

    public static class TreeRoot {
        public final Uri uri;
        public final String rootPath;
        public final String rootDocumentId;
        public DocumentNode documentRoot;

        public TreeRoot(Uri uri, String rootPath, String rootDocumentId) {
            this.uri = uri;
            this.rootPath = rootPath;
            this.rootDocumentId = rootDocumentId;
        }
    }
}