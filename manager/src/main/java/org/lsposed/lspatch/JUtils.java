package org.lsposed.lspatch;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.FileUtils;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.ArrayList;
import com.wind.meditor.utils.FileTypeUtils;

public class JUtils {
    public static void checkAndToastUser(Context context){

    }
    public static String getInstallSign(Context context,String packageName){
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            return info.signingInfo.getApkContentsSigners()[0].toCharsString();
        }catch (Exception e){
            return "";
        }

    }
    public static String getApkSign(Context context,String apkPath){
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES);
            return info.signingInfo.getApkContentsSigners()[0].toCharsString();
        }catch (Exception e){
            return "";
        }
    }
    public static boolean checkSignMatched(Context context,String packageName,String apkPath){
        return getInstallSign(context,packageName).equals(getApkSign(context,apkPath));
    }
    public static boolean checkIsApkFixedByLSP(Context context,String packageName){
        try {
            PackageManager manager = context.getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            return info.metaData == null || !info.metaData.containsKey("lspatch");
        }catch (Exception e){
            Log.i("OPatch",Log.getStackTraceString(e));
            return false;
        }
    }

    public static void installApkByPackageManager(Context context,File apkPath){
        GlobalUserHandler.mHandler.size();
        try {
            Log.i("OPatchOutput", "RequestInstall: " + apkPath);

            // Check if it's an XAPK file
            if (FileTypeUtils.isXapkFile(apkPath.getAbsolutePath())) {
                installXapkByPackageManager(context, apkPath);
                return;
            }

            // Original APK installation logic
            String e = context.getExternalCacheDir() + "/install.apk";
            File file = new File(e);
            if (file.exists()) {
                file.delete();
            }
            copy(apkPath.getAbsolutePath(), e);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            //对Android N及以上的版本做判断
            Uri apkUriN = FileProvider.getUriForFile(context,
                    context.getApplicationContext().getPackageName() + ".FileProvider", file);
            intent.addCategory("android.intent.category.DEFAULT");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);   //天假Flag 表示我们需要什么权限
            intent.setDataAndType(apkUriN, "application/vnd.android.package-archive");

            context.startActivity(intent);
        }catch (Exception e){
           Log.i("OPatchOutput", Log.getStackTraceString(e));
        }
    }
    public static void copy(String source, String dest) {

        try {
            if (!new File(source).exists()){
                return;
            }

            File f = new File(dest);
            f = f.getParentFile();
            if (!f.exists()) f.mkdirs();

            File aaa = new File(dest);
            if (aaa.exists()) aaa.delete();

            InputStream in = Files.newInputStream(new File(source).toPath());
            OutputStream out = Files.newOutputStream(new File(dest).toPath());
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            in.close();
            out.close();
        } catch (Exception ignored) {
        }
    }
    public static void uninstallApkByPackageName(Context context,String packageName){
        try {
            Uri packageURI = Uri.parse("package:" + packageName);
            Intent uninstallIntent = new Intent(Intent.ACTION_DELETE, packageURI);
            uninstallIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(uninstallIntent);
        }catch (Exception ignored){

        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public static List<String> processApkPath(Context context, List<String> paths){
        Log.i("OPatchOutput", "processApkPath: " + paths.toString());
        if (paths.size() == 1){
            String apkPath = paths.get(0);
            try (ZipFile zInp = new ZipFile(apkPath)){
                ZipEntry entry = zInp.getEntry("assets/lspatch/origin.apk");

                Log.i("OPatchOutput", "processApkPath: " + entry);
                if (entry != null){
                    String cachePath = context.getCacheDir().getAbsolutePath() + File.separator + "lspatch" + File.separator + System.currentTimeMillis()+".apk";
                    File newFile = new File(cachePath).getParentFile();
                    if (!newFile.exists()){
                        newFile.mkdirs();
                    }
                    FileOutputStream cacheFile = new FileOutputStream(cachePath);
                    FileUtils.copy(zInp.getInputStream(entry),cacheFile);
                    cacheFile.close();
                    paths.set(0,cachePath);
                }
            } catch (IOException e) {
                Log.i("OPatchOutput", "processApkPath: " + e);
                return paths;
            }
            return paths;

        }else {
            return paths;
        }
    }

    public static boolean isGenshinInstalled(Context context){
        try {
            if (!TextUtils.isEmpty(getGenshinVersion(context)))return true;
            if (!TextUtils.isEmpty(getGenshinXiaomiVersion(context)))return true;
            if (!TextUtils.isEmpty(getCloudYsVersion(context)))return true;
            if (!TextUtils.isEmpty(getGenshinBilibiliVersion(context)))return true;
            if (!TextUtils.isEmpty(getGenshinGlobalVersion(context)))return true;
        }catch (Exception ignored){ }
        return false;
    }
    public static String getFullGenshinImpactVersionInfo(Context context){
        String s = "";
        String getInfo = getGenshinVersion(context);
        if (!TextUtils.isEmpty(getInfo)){
            s += "国服 " + getInfo + " 已安装\n";
        }
        getInfo = getGenshinGlobalVersion(context);
        if (!TextUtils.isEmpty(getInfo)){
            s += "国际服 " + getInfo + " 已安装\n";
        }
        getInfo = getCloudYsVersion(context);
        if (!TextUtils.isEmpty(getInfo)){
            s += "云原神 " + getInfo + " 已安装\n";
        }
        getInfo = getGenshinBilibiliVersion(context);
        if (!TextUtils.isEmpty(getInfo)){
            s += "B服 " + getInfo + " 已安装\n";
        }
        getInfo = getGenshinXiaomiVersion(context);
        if (!TextUtils.isEmpty(getInfo)){
            s += "小米服 " + getInfo + " 已安装\n";
        }
        if (!TextUtils.isEmpty(s)){
            s = s.substring(0,s.length() -1);
        }
        return s;
    }
    private static String getCloudYsVersion(Context context){
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo("com.miHoYo.cloudgames.ys",0);
            return info.versionName;
        }catch (Exception e){
            return "";
        }
    }
    private static String getGenshinGlobalVersion(Context context){
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo("com.miHoYo.GenshinImpact",0);
            return info.versionName.substring(0,info.versionName.indexOf("_"));
        }catch (Exception ignored){
            return "";
        }
    }
    private static String getGenshinBilibiliVersion(Context context){
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo("com.miHoYo.ys.bilibili",0);
            return info.versionName.substring(0,info.versionName.indexOf("_"));
        }catch (Exception ignored){
            return "";
        }
    }
    private static String getGenshinXiaomiVersion(Context context){
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo("com.miHoYo.ys.mi",0);
            return info.versionName.substring(0,info.versionName.indexOf("_"));
        }catch (Exception ignored){
            return "";
        }
    }
    private static String getGenshinVersion(Context context){
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo("com.miHoYo.Yuanshen",0);
            return info.versionName.substring(0,info.versionName.indexOf("_"));
        }catch (Exception ignored){
            return "";
        }
    }

    /**
     * Install XAPK file by extracting and installing APKs
     */
    public static void installXapkByPackageManager(Context context, File xapkPath) {
        try {
            Log.i("OPatchOutput", "Installing XAPK: " + xapkPath);

            // Check if this is a patched XAPK that might have cached extraction info
            // For now, we'll extract normally, but this could be optimized

            // Create temporary directory for XAPK extraction
            File tempDir = new File(context.getExternalCacheDir(), "xapk_install_" + System.currentTimeMillis());
            tempDir.mkdirs();

            List<File> apkFiles = new ArrayList<>();
            File obbDir = null;
            String packageName = null;

            // Extract XAPK contents
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(xapkPath.toPath()))) {
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
                    if (entryName.endsWith(".apk")) {
                        apkFiles.add(outputFile);
                    } else if (entryName.equals("manifest.json")) {
                        // Parse package name from manifest.json for OBB directory
                        packageName = parsePackageNameFromManifest(outputFile);
                    } else if (entryName.startsWith("Android/obb/") && entryName.endsWith(".obb")) {
                        if (obbDir == null) {
                            obbDir = new File(android.os.Environment.getExternalStorageDirectory(), "Android/obb");
                        }
                    }

                    zis.closeEntry();
                }
            }

            // Install APK files
            if (!apkFiles.isEmpty()) {
                // For multiple APKs, we need to use session-based installation
                if (apkFiles.size() == 1) {
                    // Single APK - use regular installation
                    installSingleApk(context, apkFiles.get(0));
                } else {
                    // Multiple APKs - use session installation
                    installMultipleApks(context, apkFiles);
                }
            }

            // Copy OBB files if they exist and we have package name
            if (obbDir != null && packageName != null) {
                copyObbFiles(tempDir, obbDir, packageName);
            }

            // Clean up temporary directory
            deleteDirectory(tempDir);

        } catch (Exception e) {
            Log.e("OPatchOutput", "Error installing XAPK: " + Log.getStackTraceString(e));
        }
    }

    private static String parsePackageNameFromManifest(File manifestFile) {
        try {
            String content = new String(Files.readAllBytes(manifestFile.toPath()));
            // Simple JSON parsing to get package_name
            int start = content.indexOf("\"package_name\"");
            if (start != -1) {
                start = content.indexOf("\"", start + 14) + 1;
                int end = content.indexOf("\"", start);
                return content.substring(start, end);
            }
        } catch (Exception e) {
            Log.e("OPatchOutput", "Error parsing manifest: " + e.getMessage());
        }
        return null;
    }

    private static void installSingleApk(Context context, File apkFile) {
        // Use existing APK installation logic
        String cachePath = context.getExternalCacheDir() + "/install.apk";
        File cacheFile = new File(cachePath);
        if (cacheFile.exists()) {
            cacheFile.delete();
        }
        copy(apkFile.getAbsolutePath(), cachePath);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Uri apkUri = FileProvider.getUriForFile(context,
                context.getApplicationContext().getPackageName() + ".FileProvider", cacheFile);
        intent.addCategory("android.intent.category.DEFAULT");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");

        context.startActivity(intent);
    }

    private static void installMultipleApks(Context context, List<File> apkFiles) {
        try {
            // Use PackageInstaller for session-based installation of multiple APKs
            android.content.pm.PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();

            // Create installation session
            android.content.pm.PackageInstaller.SessionParams params =
                new android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(null); // Let system determine package name

            int sessionId = packageInstaller.createSession(params);
            android.content.pm.PackageInstaller.Session session = packageInstaller.openSession(sessionId);

            // Add all APK files to the session
            for (int i = 0; i < apkFiles.size(); i++) {
                File apkFile = apkFiles.get(i);
                String apkName = "base.apk";
                if (i > 0) {
                    apkName = "split_" + i + ".apk";
                }

                try (OutputStream out = session.openWrite(apkName, 0, apkFile.length());
                     InputStream in = Files.newInputStream(apkFile.toPath())) {

                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                    session.fsync(out);
                }
            }

            // Create pending intent for installation result
            Intent intent = new Intent(context, InstallResultReceiver.class);
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getBroadcast(
                context, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

            // Commit the session
            session.commit(pendingIntent.getIntentSender());
            session.close();

            Log.i("OPatchOutput", "Started session-based installation for " + apkFiles.size() + " APK files");

        } catch (Exception e) {
            Log.e("OPatchOutput", "Session-based installation failed, falling back to single APK: " + e.getMessage());
            // Fallback: install only the main APK
            if (!apkFiles.isEmpty()) {
                installSingleApk(context, apkFiles.get(0));
                Log.w("OPatchOutput", "Only main APK installed. Split APKs skipped due to installation limitations.");
            }
        }
    }

    private static void copyObbFiles(File tempDir, File obbDir, String packageName) {
        try {
            File sourceObbDir = new File(tempDir, "Android/obb/" + packageName);
            if (sourceObbDir.exists()) {
                File targetObbDir = new File(obbDir, packageName);
                targetObbDir.mkdirs();

                File[] obbFiles = sourceObbDir.listFiles();
                if (obbFiles != null) {
                    for (File obbFile : obbFiles) {
                        if (obbFile.isFile() && obbFile.getName().endsWith(".obb")) {
                            File targetFile = new File(targetObbDir, obbFile.getName());
                            copy(obbFile.getAbsolutePath(), targetFile.getAbsolutePath());
                            Log.i("OPatchOutput", "Copied OBB file: " + obbFile.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e("OPatchOutput", "Error copying OBB files: " + e.getMessage());
        }
    }

    private static void deleteDirectory(File dir) {
        if (dir.exists()) {
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

}
