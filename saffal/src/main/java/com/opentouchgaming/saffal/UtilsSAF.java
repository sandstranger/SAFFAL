package com.opentouchgaming.saffal;

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
import java.util.List;

public class UtilsSAF {
    private static final String TAG = "UtilsSAF";
    private static Context appContext;
    private static int cacheNativeFs = 0;

    private static final List<TreeRoot> treeRoots = new ArrayList<>();

    public static void setContext(@NonNull Context ctx, boolean cacheNativeFs) {
        appContext = ctx.getApplicationContext();
        UtilsSAF.cacheNativeFs = cacheNativeFs ? 1 : 0;
        System.loadLibrary("saffal");
    }

    public static Context getContext() {
        return appContext;
    }

    public static ContentResolver getContentResolver() {
        checkContext();
        return appContext.getContentResolver();
    }

    private static void checkContext() {
        if (appContext == null) {
            throw new IllegalStateException("UtilsSAF.setContext() must be called first");
        }
    }

    public static boolean addTreeRootFromUri(@NonNull Uri treeUri) {
        checkContext();

        getContentResolver().takePersistableUriPermission(treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        if (documentId == null) {
            Log.e(TAG, "Cannot extract document ID from URI: " + treeUri);
            return false;
        }

        String rootPath = guessRootPath(documentId);
        if (rootPath == null) {
            rootPath = "/saf/tree_" + Math.abs(treeUri.hashCode());
        }

        TreeRoot root = new TreeRoot(treeUri, rootPath, documentId);
        addTreeRoot(root);
        return true;
    }

    @Nullable
    private static String guessRootPath(String documentId) {
        if (TextUtils.isEmpty(documentId)) return null;
        String[] parts = documentId.split(":", 2);
        String volume = parts[0];
        String path = parts.length > 1 ? parts[1] : "";

        if (volume.equalsIgnoreCase("primary")) {
            String base = Environment.getExternalStorageDirectory().getAbsolutePath();
            if (path.isEmpty()) return base;
            if (path.startsWith("/")) path = path.substring(1);
            return base + "/" + path;
        }
        else if (volume.matches("[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}")) {
            String base = "/storage/" + volume;
            if (path.isEmpty()) return base;
            if (path.startsWith("/")) path = path.substring(1);
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
        rebuildNativePaths();
    }

    public static void removeTreeRoot(@NonNull String rootPath) {
        treeRoots.removeIf(r -> r.rootPath.equals(rootPath));
        rebuildNativePaths();
    }

    public static void clearTreeRoots() {
        treeRoots.clear();
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
            if (path.startsWith(root.rootPath)) {
                return root;
            }
        }
        return null;
    }

    @Nullable
    public static DocumentNode findDocumentNode(String fullPath) {
        TreeRoot root = getTreeRootForPath(fullPath);
        if (root == null) return null;
        String relativePath = getDocumentPath(fullPath, root);
        return DocumentNode.findDocumentNode(root.documentRoot, relativePath);
    }

    public static void saveTreeRoots() {
        checkContext();
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
        checkContext();
        SharedPreferences prefs = appContext.getSharedPreferences("utilsSAF", Context.MODE_PRIVATE);
        int count = prefs.getInt("count", 0);

        if (count > 0) {
            treeRoots.clear();
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

    public static boolean isInSAFRoot(String path) {
        return getTreeRootForPath(path) != null;
    }

    public static boolean isRootOfSAFRoot(String path) {
        if (treeRoots.isEmpty()) return false;
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
            if (match) return true;
        }
        return false;
    }

    @Nullable
    public static String getRealPathFromUri(@NonNull Uri uri) {
        if (appContext == null) return null;

        try {
            String docId = DocumentsContract.getDocumentId(uri);
            if (docId != null) {
                if (docId.startsWith("primary:")) {
                    String relative = docId.substring("primary:".length());
                    if (relative.startsWith("/")) relative = relative.substring(1);
                    return Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + relative;
                }
                if (docId.matches("[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}:.*")) {
                    int colon = docId.indexOf(':');
                    String vol = docId.substring(0, colon);
                    String rel = docId.substring(colon + 1);
                    if (rel.startsWith("/")) rel = rel.substring(1);
                    return "/storage/" + vol + "/" + rel;
                }
            }
        } catch (Exception ignored) {}

        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd != null) {
                String rawPath = getFdPath(pfd.getFd());
                pfd.close();
                if (!TextUtils.isEmpty(rawPath)) {
                    String normalized = normalizeInternalPath(rawPath);
                    if (normalized != null) return normalized;
                    return rawPath;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static String normalizeInternalPath(String path) {
        if (path.startsWith("/mnt/user/0/emulated/")) {
            return "/storage/emulated/" + path.substring("/mnt/user/0/emulated/".length());
        }
        if (path.startsWith("/mnt/media_rw/")) {
            String rest = path.substring("/mnt/media_rw/".length());
            int slashIndex = rest.indexOf('/');
            if (slashIndex > 0) {
                String vol = rest.substring(0, slashIndex);
                String after = rest.substring(slashIndex);
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
        if (fullPath.length() > root.rootPath.length()) {
            return fullPath.substring(root.rootPath.length() + 1); // убираем ведущий /
        }
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

    private static void DBG(String str) {
        Log.d(TAG, str);
    }
}