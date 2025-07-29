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
        public List<String> splitApkPaths = new ArrayList<>();
        public List<String> obbPaths = new ArrayList<>();
        public JsonObject originalManifest;
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
                    } else {
                        // This is a split APK
                        xapkInfo.splitApkPaths.add(outputFile.getAbsolutePath());
                    }
                } else if (entryName.startsWith(ANDROID_OBB_DIR) && entryName.endsWith(".obb")) {
                    xapkInfo.obbPaths.add(outputFile.getAbsolutePath());
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
     * Repack XAPK with patched APK
     */
    public static void repackXAPK(XAPKInfo xapkInfo, File outputXapkFile, File tempDir) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputXapkFile))) {
            
            // Add manifest.json
            addFileToZip(zos, MANIFEST_JSON, xapkInfo.originalManifest.toString().getBytes(StandardCharsets.UTF_8));
            
            // Add main APK
            File mainApkFile = new File(xapkInfo.mainApkPath);
            addFileToZip(zos, mainApkFile.getName(), mainApkFile);
            
            // Add split APKs
            for (String splitApkPath : xapkInfo.splitApkPaths) {
                File splitApkFile = new File(splitApkPath);
                addFileToZip(zos, splitApkFile.getName(), splitApkFile);
            }
            
            // Add OBB files
            for (String obbPath : xapkInfo.obbPaths) {
                File obbFile = new File(obbPath);
                String relativePath = ANDROID_OBB_DIR + xapkInfo.packageName + "/" + obbFile.getName();
                addFileToZip(zos, relativePath, obbFile);
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
