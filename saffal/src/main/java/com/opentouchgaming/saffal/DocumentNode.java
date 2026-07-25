package com.opentouchgaming.saffal;

import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DocumentNode {
    static final String TAG = "DocumentNode";
    static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    public String name;
    public String documentId;
    public boolean isDirectory;
    public long size;
    public long modifiedDate;
    public DocumentNode parent;
    public Uri treeUri;
    private Map<String, DocumentNode> childrenMap;

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Nullable
    public static DocumentNode findDocumentNode(@NonNull DocumentNode rootNode, @Nullable String documentPath) {
        if (documentPath == null || !rootNode.isDirectory) return null;

        String[] parts = documentPath.split("/", -1);
        DocumentNode current = rootNode;

        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (current == null || !current.isDirectory) return null;
            current = current.findChild(part);
            if (current == null) return null;
        }
        return current;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Nullable
    public static DocumentNode createAllNodes(@NonNull DocumentNode rootNode, @Nullable String documentPath)
            throws FileNotFoundException {
        if (documentPath == null || !rootNode.isDirectory) return null;

        String[] parts = documentPath.split("/", -1);
        DocumentNode current = rootNode;

        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (current == null || !current.isDirectory) {
                Log.w(TAG, "createAllNodes: current not a directory");
                return null;
            }
            DocumentNode next = current.findChild(part);
            if (next == null) {
                next = current.createChild(true, part);
                if (next == null) return null;
            }
            current = next;
        }
        return current;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Nullable
    public synchronized DocumentNode findChild(String name) {
        if (!isDirectory) return null;
        ensureChildrenLoaded();
        if (childrenMap == null || childrenMap.isEmpty()) return null;
        DocumentNode exact = childrenMap.get(name);
        if (exact != null) return exact;
        for (Map.Entry<String, DocumentNode> entry : childrenMap.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @NonNull
    public synchronized List<DocumentNode> getChildren() {
        if (!isDirectory) return Collections.emptyList();
        ensureChildrenLoaded();
        if (childrenMap == null || childrenMap.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(childrenMap.values());
    }

    private void ensureChildrenLoaded() {
        if (!isDirectory) return;
        if (childrenMap == null) {
            loadChildren();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void loadChildren() {
        childrenMap = new HashMap<>();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        try (Cursor cursor = UtilsSAF.getContentResolver().query(childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_SIZE
                }, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                DocumentNode node = new DocumentNode();
                node.name = cursor.getString(0);
                String mime = cursor.getString(1);
                node.isDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                node.documentId = cursor.getString(2);
                node.modifiedDate = cursor.getLong(3);
                node.size = cursor.getLong(4);
                node.treeUri = this.treeUri;
                node.parent = this;
                childrenMap.put(node.name, node);
            }
        } catch (Exception e) {
            Log.e(TAG, "loadChildren failed: " + e.getMessage());
            childrenMap = null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Nullable
    public synchronized DocumentNode createChild(boolean directory, String name) throws FileNotFoundException {
        if (!isDirectory) {
            throw new FileNotFoundException("Parent is not a directory");
        }

        DocumentNode existing = findChild(name);
        if (existing != null) {
            return existing;
        }

        Uri parentUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String mime = directory ? DocumentsContract.Document.MIME_TYPE_DIR : DEFAULT_MIME_TYPE;
        DocumentsContract.createDocument(UtilsSAF.getContentResolver(), parentUri, mime, name);


        clearCache();
        return findChild(name);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public synchronized boolean deleteChild(String name) throws FileNotFoundException {
        if (!isDirectory) {
            throw new FileNotFoundException("Not a directory");
        }
        DocumentNode child = findChild(name);
        if (child == null) {
            throw new FileNotFoundException("Child not found: " + name);
        }

        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId);
        boolean result = DocumentsContract.deleteDocument(UtilsSAF.getContentResolver(), docUri);
        if (result) {
            clearCache();
        }
        return result;
    }

    public synchronized void clearCache() {
        childrenMap = null;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public InputStream getInputStream() throws FileNotFoundException {
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        return UtilsSAF.getContentResolver().openInputStream(docUri);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public OutputStream getOutputStream() throws FileNotFoundException {
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        return UtilsSAF.getContentResolver().openOutputStream(docUri, "wt");
    }

    private static void DBG(String msg) {
        Log.d(TAG, msg);
    }
}