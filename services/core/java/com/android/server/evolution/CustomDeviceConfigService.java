/*
 * Copyright (C) 2023 The PixelExperience Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.evolution;

import android.content.Context;
import com.android.server.SystemService;

import com.android.internal.os.BackgroundThread;
import com.android.internal.util.evolution.DeviceConfigUtils;

public class CustomDeviceConfigService extends SystemService {

    private static final String TAG = "CustomDeviceConfigService";

    private final Context mContext;

    public CustomDeviceConfigService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        DeviceConfigUtils.setDefaultProperties(null, null);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            // Re-apply in case something reset them during boot; not on the main thread.
            BackgroundThread.getHandler().post(
                    () -> DeviceConfigUtils.setDefaultProperties(null, null));
        }
    }
}
