package com.opentouchgaming.saffal;

import android.os.Build;
import androidx.annotation.RequiresApi;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class FileJNI {
    public static native void initSAFPaths(String[] SAFPaths, int cacheNativeFs);
    public static native void initSafePaths(String[] paths);
    public static native void nativeSetSafEnabled(boolean enabled);

    public static int fopen(final String filePath, final String mode) {
        boolean write = mode.contains("w") || mode.contains("a");
        FileSAF fileSAF = new FileSAF(filePath);

        if (fileSAF.isRealFile()) {
            return -1;
        }

        if (write && !fileSAF.exists()) {
            try {
                fileSAF.createNewFile();
            } catch (IOException e) {
                return -1;
            }
        }

        return fileSAF.getFd(write, true);
    }

    public static int mkdir(String path) {
        FileSAF fileSAF = new FileSAF(path);
        if (UtilsSAF.isRootOfSAFRoot(fileSAF.getPath())) {
            return 0;
        }

        if (fileSAF.isRealFile()) {
            return -1;
        }

        return fileSAF.mkdirs() ? 0 : 1;
    }

    public static int exists(String path) {
        FileSAF fileSAF = new FileSAF(path);
        return fileSAF.exists() ? 1 : 0;
    }

    public static int delete(String path) {
        FileSAF fileSAF = new FileSAF(path);
        return fileSAF.delete() ? 0 : 1;
    }

    public static int rename(String oldFilename, String newFilename) {
        FileSAF oldFile = new FileSAF(oldFilename);
        FileSAF newFile = new FileSAF(newFilename);

        FileSAF oldParent = oldFile.getParentFile();
        FileSAF newParent = newFile.getParentFile();

        if (oldParent == null || newParent == null) {
            return -1;
        }

        String oldParentPath = oldParent.getPath();
        String newParentPath = newParent.getPath();

        if (oldParentPath.equals(newParentPath)) {
            try {
                if (newFile.exists()) newFile.delete();
                oldFile.rename(newFile.getName());
                return 0;
            } catch (IOException e) {
                return -1;
            }
        } else {
            if (!oldFile.isRealFile() && !newFile.isRealFile()) {
                if (!oldFile.exists()) return -1;
                try {
                    if (newFile.exists()) newFile.delete();
                    newFile.createNewFile();

                    try (InputStream in = oldFile.getInputStream();
                         DataOutputStream out = new DataOutputStream(newFile.getOutputStream())) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }

                    oldFile.delete();
                    oldParent.clearCache();
                    newParent.clearCache();

                    return 0;
                } catch (IOException e) {
                    try { newFile.delete(); } catch (Exception ignored) {}
                    return -1;
                }
            } else {
                return -1;
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public static String[] opendir(String path) {
        FileSAF fileSAF = new FileSAF(path);
        if (!fileSAF.isRealFile() && fileSAF.isDirectory()) {
            FileSAF[] files = fileSAF.listFiles();
            if (files == null) {
                return new String[0];
            }

            String[] ret = new String[files.length];
            for (int n = 0; n < files.length; n++) {
                ret[n] = (files[n].isDirectory() ? "D" : "F") + files[n].getName();
            }
            return ret;
        } else {
            return new String[0];
        }
    }
}