package com.limelight;

import android.app.Application;
import android.widget.Toast;

import com.limelight.profiles.ProfilesManager;
import com.limelight.dualsense.DualSenseBridge;
import com.limelight.dualsense.DualSenseAudioBridge;

public class ArtemisApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DualSenseBridge.initialize(this);
        DualSenseAudioBridge.initialize(this);
        ProfilesManager profilesManager = ProfilesManager.getInstance();
        if (!profilesManager.load(this)) {
            Toast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show();
        }
    }
}
