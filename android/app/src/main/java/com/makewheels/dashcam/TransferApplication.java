package com.makewheels.dashcam;
import android.app.Application;
import android.net.*;
import android.content.Context;
import androidx.work.*;

public final class TransferApplication extends Application {
    @Override public void onCreate(){super.onCreate();ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback(){
            @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){
                if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!getSharedPreferences("settings",0).getBoolean("upload_paused",false)&&!TransferEngine.BUSY.get()){
                    Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
                    WorkManager.getInstance(TransferApplication.this).enqueueUniqueWork("wifi-arrived",ExistingWorkPolicy.KEEP,new OneTimeWorkRequest.Builder(UploadWorker.class).setConstraints(constraints).build());
                }
            }
        });
    }
}
