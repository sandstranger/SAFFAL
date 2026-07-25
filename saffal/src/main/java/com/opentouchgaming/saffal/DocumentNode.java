package com.opentouchgaming.saffal;

import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class DocumentNode {
    static final String TAG = "DocumentNode";
    static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    public String name;
    public String documentId;
    public boolean exists;
    public boolean isDirectory;
    public long size;
    public long modifiedDate;
    public DocumentNode parent;
    public Uri treeUri;

    private List<DocumentNode> children;

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public static DocumentNode findDocumentNode(DocumentNode rootNode, String documentPath) {
        if (documentPath == null || rootNode == null) return null;
        if (!rootNode.isDirectory) return null;

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
    public static DocumentNode createAllNodes(DocumentNode rootNode, String documentPath)
            throws FileNotFoundException {
        DBG("createAllNodes: " + documentPath);
        if (documentPath == null || rootNode == null || !rootNode.isDirectory) return null;

        String[] parts = documentPath.split("/", -1);
        DocumentNode current = rootNode;

        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (current == null || !current.isDirectory) {
                DBG("createAllNodes: current not a directory");
                return null;
            }
            DocumentNode next = current.findChild(part);
            if (next == null) {
                next = current.createChild(true, part);
                if (next == null) {
                    DBG("createAllNodes: failed to create " + part);
                    return null;
                }
            }
            current = next;
        }
        return current;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public synchronized DocumentNode findChild(String name) {
        if (!isDirectory) return null;
        if (children == null) {
            loadChildren();
        }
        if (children == null) return null;

        for (DocumentNode child : children) {
            if (child.name.equals(name)) return child;
        }
        for (DocumentNode child : children) {
            if (child.name.equalsIgnoreCase(name)) return child;
        }
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public synchronized List<DocumentNode> getChildren() {
        if (isDirectory && children == null) {
            loadChildren();
        }
        if (children == null) return new ArrayList<>();
        return new ArrayList<>(children);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void loadChildren() {
        if (!isDirectory) return;
        children = new ArrayList<>();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        Cursor cursor = null;
        try {
            cursor = UtilsSAF.getContentResolver().query(childrenUri,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                            DocumentsContract.Document.COLUMN_SIZE
                    }, null, null, null);
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
                node.exists = true;
                children.add(node);
            }
        } catch (Exception e) {
            Log.e(TAG, "loadChildren failed: " + e.getMessage());
            children = null;
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Exception ignored) {}
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public synchronized DocumentNode createChild(boolean directory, String name) throws FileNotFoundException {
        DBG("createChild: " + name + " dir=" + directory);
        if (!isDirectory) {
            throw new FileNotFoundException("Parent is not a directory");
        }
        if (findChild(name) != null) {
            DBG("createChild: already exists, returning existing");
            return findChild(name);
        }

        Uri parentUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        try {
            String mime = directory ? DocumentsContract.Document.MIME_TYPE_DIR : DEFAULT_MIME_TYPE;
            DocumentsContract.createDocument(UtilsSAF.getContentResolver(), parentUri, mime, name);
        } catch (Exception e) {
            Log.e(TAG, "createChild failed: " + e.getMessage());
            throw new FileNotFoundException("Cannot create " + name);
        }

        children = null;
        return findChild(name);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public synchronized boolean deleteChild(String name) throws FileNotFoundException {
        DBG("deleteChild: " + name);
        if (!isDirectory) {
            throw new FileNotFoundException("Not a directory");
        }
        DocumentNode child = findChild(name);
        if (child == null) {
            throw new FileNotFoundException("Child not found: " + name);
        }

        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId);
        try {
            boolean result = DocumentsContract.deleteDocument(UtilsSAF.getContentResolver(), docUri);
            if (result) {
                children = null;
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "deleteChild failed: " + e.getMessage());
            return false;
        }
    }

    public synchronized void clearCache() {
        children = null;
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