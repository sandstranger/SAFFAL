package com.opentouchgaming.saffal;

import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class FileSAF extends File {

    private final String TAG = "FileSAF";
    private final String fullPath;
    private final boolean isRealFile;
    private boolean isDirectory;
    private DocumentNode documentNode;
    private ParcelFileDescriptor parcelFileDescriptor;
    private int fd = -1;

    public FileSAF(@NonNull String path) {
        super(path);
        this.fullPath = canonicalize(path);
        this.isRealFile = !UtilsSAF.isSafEnabled() || !UtilsSAF.isInSAFRoot(fullPath);
    }

    public FileSAF(@NonNull String parent, @NonNull String child) {
        this(parent + File.separator + child);
    }

    public FileSAF(@NonNull File parent, @NonNull String child) {
        this(parent.getAbsolutePath(), child);
    }

    public FileSAF(@NonNull FileSAF parent, @NonNull String child) {
        this((File) parent, child);
    }

    private FileSAF(@NonNull String path, boolean alreadyCanonical) {
        super(path);
        if (alreadyCanonical) {
            this.fullPath = path;
        } else {
            this.fullPath = canonicalize(path);
        }
        this.isRealFile = !UtilsSAF.isSafEnabled() || !UtilsSAF.isInSAFRoot(fullPath);
    }


    @Override
    public String getPath() {
        return fullPath;
    }

    @Override
    public String getAbsolutePath() {
        return fullPath;
    }

    @Override
    public String getCanonicalPath() {
        return fullPath;
    }

    @NonNull
    @Override
    public String getParent() {
        File parentFile = new File(fullPath).getParentFile();
        return parentFile != null ? parentFile.getAbsolutePath() : "";
    }

    @NonNull
    @Override
    public FileSAF getParentFile() {
        String p = getParent();
        return p.isEmpty() ? null : new FileSAF(p, true);
    }

    @NonNull
    @Override
    public String getName() {
        return new File(fullPath).getName();
    }

    @Override
    public String toString() {
        return fullPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileSAF)) return false;
        return fullPath.equals(((FileSAF) o).fullPath);
    }

    @Override
    public int hashCode() {
        return fullPath.hashCode();
    }

    public boolean isRealFile() {
        return isRealFile;
    }


    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean exists() {
        if (isRealFile) {
            if (super.exists()) return true;
            try (FileInputStream fis = new FileInputStream(this)) {
                return true;
            } catch (IOException ignored) {
                return false;
            }
        } else {

            updateDocumentNode(false);
            return documentNode != null;
        }
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean isFile() {
        if (isRealFile) return super.isFile();
        updateDocumentNode(false);
        return documentNode != null && !documentNode.isDirectory;
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean isDirectory() {
        if (isRealFile) return super.isDirectory();
        updateDocumentNode(false);
        return documentNode != null && documentNode.isDirectory;
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean canRead() {
        return exists();
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean canWrite() {
        if (isRealFile) return super.canWrite();

        return exists();
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public long length() {
        if (isRealFile) return super.length();
        updateDocumentNode(false);
        return documentNode != null ? documentNode.size : 0L;
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public long lastModified() {
        if (isRealFile) return super.lastModified();
        updateDocumentNode(false);
        return documentNode != null ? documentNode.modifiedDate : 0L;
    }


    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean createNewFile() throws IOException {
        if (isRealFile) return super.createNewFile();
        DocumentNode parentNode = UtilsSAF.findDocumentNode(getParent());
        if (parentNode != null && parentNode.isDirectory) {
            return parentNode.createChild(false, getName()) != null;
        } else {
            throw new IOException("Parent directory is invalid or not found in SAF");
        }
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean mkdirs() {
        if (isRealFile) return super.mkdirs();
        updateDocumentNode(false);
        if (documentNode != null) return true;

        UtilsSAF.TreeRoot root = UtilsSAF.getTreeRootForPath(fullPath);
        if (root == null) return false;

        try {
            String relativePath = UtilsSAF.getDocumentPath(fullPath);
            documentNode = DocumentNode.createAllNodes(root.documentRoot, relativePath);
            if (documentNode != null) {
                isDirectory = documentNode.isDirectory;
                return true;
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "mkdirs failed: " + e.getMessage());
        }
        return false;
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean delete() {
        if (isRealFile) return super.delete();
        DocumentNode parentNode = UtilsSAF.findDocumentNode(getParent());
        if (parentNode != null && parentNode.isDirectory) {
            try {
                boolean ok = parentNode.deleteChild(getName());
                if (ok) {
                    parentNode.clearCache();
                    documentNode = null;
                }
                return ok;
            } catch (FileNotFoundException e) {
                Log.e(TAG, "delete: " + e.getMessage());
                return false;
            }
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public boolean rename(String newName) throws IOException {
        if (isRealFile) {
            File dest = new File(getParent(), newName);
            return super.renameTo(dest);
        }
        updateDocumentNode(false);
        if (documentNode != null) {
            UtilsSAF.renameDocument(documentNode.documentId, documentNode.treeUri, newName);
            DocumentNode parentNode = UtilsSAF.findDocumentNode(getParent());
            if (parentNode != null) parentNode.clearCache();
            documentNode = null;
            return true;
        }
        return false;
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public String[] list() {
        FileSAF[] files = listFiles();
        if (files == null) return null;
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = files[i].getName();
        return names;
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public FileSAF[] listFiles() {
        if (isRealFile) {
            File[] realFiles = super.listFiles();
            if (realFiles == null) return null;
            FileSAF[] result = new FileSAF[realFiles.length];
            for (int i = 0; i < realFiles.length; i++) {
                result[i] = new FileSAF(realFiles[i].getAbsolutePath(), true);
            }
            return result;
        } else {
            updateDocumentNode(false);
            if (documentNode != null && documentNode.isDirectory) {
                List<DocumentNode> children = documentNode.getChildren();
                FileSAF[] result = new FileSAF[children.size()];
                for (int i = 0; i < children.size(); i++) {
                    DocumentNode child = children.get(i);
                    result[i] = new FileSAF(fullPath + "/" + child.name, true);
                }
                return result;
            }
            return null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public InputStream getInputStream() throws FileNotFoundException {
        if (isRealFile) return new FileInputStream(this);
        updateDocumentNode(false);
        if (documentNode != null) return documentNode.getInputStream();
        throw new FileNotFoundException(fullPath);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public OutputStream getOutputStream() throws FileNotFoundException {
        if (isRealFile) return new FileOutputStream(this);
        updateDocumentNode(false);
        if (documentNode != null) return documentNode.getOutputStream();
        throw new FileNotFoundException(fullPath);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public int getFd(boolean write, boolean detach) {
        closeCurrentFd();

        if (isRealFile) {
            try {
                parcelFileDescriptor = ParcelFileDescriptor.open(
                        new File(fullPath),
                        write ? ParcelFileDescriptor.MODE_READ_WRITE : ParcelFileDescriptor.MODE_READ_ONLY);
                fd = detach ? parcelFileDescriptor.detachFd() : parcelFileDescriptor.getFd();
                return fd;
            } catch (FileNotFoundException e) {
                Log.e(TAG, "getFd real file not found: " + e.getMessage());
                return -1;
            }
        } else {
            updateDocumentNode(false);
            if (documentNode != null) {
                try {
                    parcelFileDescriptor = UtilsSAF.getParcelDescriptor(
                            documentNode.documentId, documentNode.treeUri, write);
                    fd = detach ? parcelFileDescriptor.detachFd() : parcelFileDescriptor.getFd();
                    return fd;
                } catch (IOException e) {
                    Log.e(TAG, "getFd SAF error: " + e.getMessage());
                    return -1;
                }
            }
            return -1;
        }
    }

    public synchronized void closeCurrentFd() {
        if (parcelFileDescriptor != null) {
            try {
                parcelFileDescriptor.close();
            } catch (IOException ignored) {
            }
            parcelFileDescriptor = null;
            fd = -1;
        }
    }

    @Nullable
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public File getRealFile() {
        if (isRealFile) return new File(fullPath);
        if (!exists()) return null;
        int tmpFd = getFd(false, false);
        if (tmpFd < 0) return null;
        String realPath = UtilsSAF.getFdPath(tmpFd);
        closeCurrentFd();
        return (realPath != null && !realPath.isEmpty()) ? new File(realPath) : null;
    }

    public void clearCache() {
        if (!isRealFile) {
            updateDocumentNode(false);
            if (documentNode != null && documentNode.isDirectory) {
                documentNode.clearCache();
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private synchronized void updateDocumentNode(boolean forceUpdate) {
        if (isRealFile) return;
        if (documentNode == null || forceUpdate) {
            documentNode = UtilsSAF.findDocumentNode(fullPath);
            if (documentNode != null) {
                isDirectory = documentNode.isDirectory;
            }
        }
    }

    private String canonicalize(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException e) {
            return new File(path).getAbsolutePath();
        }
    }
}