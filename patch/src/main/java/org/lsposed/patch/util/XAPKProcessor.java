package org.lsposed.patch.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.lsposed.patch.LSPatch;
import org.lsposed.patch.PatchError;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * XAPK file processor for handling XAPK format files
 */
public class XAPKProcessor {
    
    private static final String MANIFEST_JSON = "manifest.json";
    private static final String ANDROID_OBB_DIR = "Android/obb/";
    
    public static class XAPKInfo {
        public String packageName;
        public String versionName;
        public int versionCode;
        public String mainApkPath;
        public String mainApkOriginalName;  // 保存原始APK文件名
        public List<String> splitApkPaths = new ArrayList<>();
        public List<String> splitApkOriginalNames = new ArrayList<>();  // 保存原始split APK文件名
        public List<String> obbPaths = new ArrayList<>();
        public List<String> obbOriginalPaths = new ArrayList<>();  // 保存原始OBB相对路径
        public JsonObject originalManifest;
        public File tempDir;  // 保存临时目录引用，方便安装时使用
    }
    
    /**
     * Extract XAPK file to temporary directory and return XAPK info
     */
    public static XAPKInfo extractXAPK(File xapkFile, File tempDir) throws PatchError, IOException {
        if (!xapkFile.exists()) {
            throw new PatchError("XAPK file does not exist: " + xapkFile.getAbsolutePath());
        }
        
        tempDir.mkdirs();
        XAPKInfo xapkInfo = new XAPKInfo();
        xapkInfo.tempDir = tempDir;
        
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(xapkFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();
                File outputFile = new File(tempDir, entryName);
                
                if (entry.isDirectory()) {
                    outputFile.mkdirs();
                    continue;
                }
                
                // Create parent directories
                outputFile.getParentFile().mkdirs();
                
                // Extract file
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
                
                // Categorize files
                if (MANIFEST_JSON.equals(entryName)) {
                    parseManifestJson(outputFile, xapkInfo);
                } else if (entryName.endsWith(".apk")) {
                    // Determine if this is the main APK or a split APK
                    String fileName = new File(entryName).getName();
                    if (fileName.equals("base.apk") ||
                        (!fileName.startsWith("split_") && xapkInfo.mainApkPath == null)) {
                        // This is the main APK
                        xapkInfo.mainApkPath = outputFile.getAbsolutePath();
                        xapkInfo.mainApkOriginalName = entryName;  // 保存原始路径
                    } else {
                        // This is a split APK
                        xapkInfo.splitApkPaths.add(outputFile.getAbsolutePath());
                        xapkInfo.splitApkOriginalNames.add(entryName);  // 保存原始路径
                    }
                } else if (entryName.startsWith(ANDROID_OBB_DIR) && entryName.endsWith(".obb")) {
                    xapkInfo.obbPaths.add(outputFile.getAbsolutePath());
                    xapkInfo.obbOriginalPaths.add(entryName);  // 保存原始相对路径
                }
                
                zis.closeEntry();
            }
        }
        
        if (xapkInfo.mainApkPath == null) {
            throw new PatchError("No main APK found in XAPK file");
        }
        
        return xapkInfo;
    }
    
    /**
     * Parse manifest.json file
     */
    private static void parseManifestJson(File manifestFile, XAPKInfo xapkInfo) throws IOException {
        String content = new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8);
        Gson gson = new Gson();
        JsonObject manifest = gson.fromJson(content, JsonObject.class);
        
        xapkInfo.originalManifest = manifest;
        xapkInfo.packageName = manifest.get("package_name").getAsString();
        xapkInfo.versionName = manifest.get("version_name").getAsString();
        xapkInfo.versionCode = manifest.get("version_code").getAsInt();
    }
    
    /**
     * Patch the main APK in XAPK
     */
    public static void patchMainApk(XAPKInfo xapkInfo, LSPatch patcher, File tempDir) throws PatchError, IOException {
        File mainApkFile = new File(xapkInfo.mainApkPath);
        File patchedApkFile = new File(tempDir, "patched_" + mainApkFile.getName());
        
        // Patch the main APK
        patcher.patch(mainApkFile, patchedApkFile);
        
        // Replace the original APK path with patched one
        xapkInfo.mainApkPath = patchedApkFile.getAbsolutePath();
    }
    
    /**
     * Repack XAPK with patched APK, preserving original structure
     */
    public static void repackXAPK(XAPKInfo xapkInfo, File outputXapkFile, File tempDir) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputXapkFile))) {

            // Add manifest.json
            addFileToZip(zos, MANIFEST_JSON, xapkInfo.originalManifest.toString().getBytes(StandardCharsets.UTF_8));

            // Add main APK with original name/path
            File mainApkFile = new File(xapkInfo.mainApkPath);
            String mainApkEntryName = xapkInfo.mainApkOriginalName != null ?
                xapkInfo.mainApkOriginalName : mainApkFile.getName();
            addFileToZip(zos, mainApkEntryName, mainApkFile);

            // Add split APKs with original names/paths
            for (int i = 0; i < xapkInfo.splitApkPaths.size(); i++) {
                File splitApkFile = new File(xapkInfo.splitApkPaths.get(i));
                String splitApkEntryName = i < xapkInfo.splitApkOriginalNames.size() ?
                    xapkInfo.splitApkOriginalNames.get(i) : splitApkFile.getName();
                addFileToZip(zos, splitApkEntryName, splitApkFile);
            }

            // Add OBB files with original paths
            for (int i = 0; i < xapkInfo.obbPaths.size(); i++) {
                File obbFile = new File(xapkInfo.obbPaths.get(i));
                String obbEntryPath = i < xapkInfo.obbOriginalPaths.size() ?
                    xapkInfo.obbOriginalPaths.get(i) :
                    (ANDROID_OBB_DIR + xapkInfo.packageName + "/" + obbFile.getName());
                addFileToZip(zos, obbEntryPath, obbFile);
            }
        }
    }
    
    /**
     * Add file to ZIP output stream
     */
    private static void addFileToZip(ZipOutputStream zos, String entryName, File file) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                zos.write(buffer, 0, len);
            }
        }
        
        zos.closeEntry();
    }
    
    /**
     * Add byte array to ZIP output stream
     */
    private static void addFileToZip(ZipOutputStream zos, String entryName, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }
    
    /**
     * Install XAPK directly from XAPKInfo (reuse extracted files)
     * This avoids re-extracting the XAPK for installation
     */
    public static void installFromXAPKInfo(android.content.Context context, XAPKInfo xapkInfo) throws IOException {
        java.util.List<java.io.File> apkFiles = new java.util.ArrayList<>();

        // Add main APK
        if (xapkInfo.mainApkPath != null) {
            apkFiles.add(new java.io.File(xapkInfo.mainApkPath));
        }

        // Add split APKs
        for (String splitApkPath : xapkInfo.splitApkPaths) {
            apkFiles.add(new java.io.File(splitApkPath));
        }

        if (apkFiles.isEmpty()) {
            throw new IOException("No APK files found in XAPK");
        }

        try {
            // Install APK files
            if (apkFiles.size() == 1) {
                // Single APK installation
                installSingleApkFromInfo(context, apkFiles.get(0));
            } else {
                // Multiple APKs installation
                installMultipleApksFromInfo(context, apkFiles);
            }

            // Copy OBB files if they exist
            if (!xapkInfo.obbPaths.isEmpty() && xapkInfo.packageName != null) {
                copyObbFilesFromInfo(context, xapkInfo);
            }

        } catch (Exception e) {
            throw new IOException("Failed to install XAPK: " + e.getMessage(), e);
        }
    }

    private static void installSingleApkFromInfo(android.content.Context context, java.io.File apkFile) throws IOException {
        // Use the same logic as JUtils.installSingleApk
        String cachePath = context.getExternalCacheDir() + "/install.apk";
        java.io.File cacheFile = new java.io.File(cachePath);
        if (cacheFile.exists()) {
            cacheFile.delete();
        }

        // Copy APK to cache
        try (java.io.FileInputStream fis = new java.io.FileInputStream(apkFile);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        }

        // Create install intent
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
        intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);

        android.net.Uri apkUri = androidx.core.content.FileProvider.getUriForFile(context,
                context.getApplicationContext().getPackageName() + ".FileProvider", cacheFile);
        intent.addCategory("android.intent.category.DEFAULT");
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");

        context.startActivity(intent);
    }

    private static void installMultipleApksFromInfo(android.content.Context context, java.util.List<java.io.File> apkFiles) throws IOException {
        // Use session-based installation for multiple APKs
        try {
            android.content.pm.PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();

            android.content.pm.PackageInstaller.SessionParams params =
                new android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);

            int sessionId = packageInstaller.createSession(params);
            android.content.pm.PackageInstaller.Session session = packageInstaller.openSession(sessionId);

            // Add all APK files to session
            for (int i = 0; i < apkFiles.size(); i++) {
                java.io.File apkFile = apkFiles.get(i);
                String apkName = i == 0 ? "base.apk" : "split_" + i + ".apk";

                try (java.io.OutputStream out = session.openWrite(apkName, 0, apkFile.length());
                     java.io.FileInputStream in = new java.io.FileInputStream(apkFile)) {

                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                    session.fsync(out);
                }
            }

            // Create pending intent for installation result
            android.content.Intent intent = new android.content.Intent(context, org.lsposed.lspatch.InstallResultReceiver.class);
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getBroadcast(
                context, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

            session.commit(pendingIntent.getIntentSender());
            session.close();

        } catch (Exception e) {
            // Fallback to single APK installation
            if (!apkFiles.isEmpty()) {
                installSingleApkFromInfo(context, apkFiles.get(0));
            }
        }
    }

    private static void copyObbFilesFromInfo(android.content.Context context, XAPKInfo xapkInfo) {
        try {
            java.io.File obbDir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Android/obb");
            java.io.File targetObbDir = new java.io.File(obbDir, xapkInfo.packageName);
            targetObbDir.mkdirs();

            for (String obbPath : xapkInfo.obbPaths) {
                java.io.File obbFile = new java.io.File(obbPath);
                if (obbFile.exists() && obbFile.getName().endsWith(".obb")) {
                    java.io.File targetFile = new java.io.File(targetObbDir, obbFile.getName());

                    try (java.io.FileInputStream fis = new java.io.FileInputStream(obbFile);
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(targetFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = fis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Log error but don't fail the installation
            android.util.Log.e("XAPKProcessor", "Error copying OBB files: " + e.getMessage());
        }
    }

    /**
     * Clean up temporary directory
     */
    public static void cleanupTempDir(File tempDir) {
        if (tempDir.exists()) {
            deleteDirectory(tempDir);
        }
    }
    
    private static void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
}
