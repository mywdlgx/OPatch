package org.lsposed.lspatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/**
 * Broadcast receiver to handle installation results for XAPK files
 */
public class InstallResultReceiver extends BroadcastReceiver {
    
    private static final String TAG = "InstallResultReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // User action required - start the confirmation activity
                Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirmIntent);
                        Log.i(TAG, "Started user confirmation activity for package: " + packageName);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to start confirmation activity: " + e.getMessage());
                    }
                }
                break;
                
            case PackageInstaller.STATUS_SUCCESS:
                Log.i(TAG, "XAPK installation successful for package: " + packageName);
                // You can add notification or callback here
                break;
                
            case PackageInstaller.STATUS_FAILURE:
            case PackageInstaller.STATUS_FAILURE_ABORTED:
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
            case PackageInstaller.STATUS_FAILURE_INVALID:
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                Log.e(TAG, "XAPK installation failed for package: " + packageName + 
                          ", Status: " + status + ", Message: " + message);
                // You can add error handling or notification here
                break;
                
            default:
                Log.w(TAG, "Unknown installation status: " + status + " for package: " + packageName);
                break;
        }
    }
}
