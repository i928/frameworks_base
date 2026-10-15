/*
 * Copyright (C) 2023 The Pixel Experience Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.evolution;

import android.content.res.Resources;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.android.internal.util.ArrayUtils;

/**
 * @hide
 */
public final class DeviceConfigUtils {

    private static final String TAG = DeviceConfigUtils.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static Boolean sEnableDeviceConfigUtils =
            SystemProperties.getBoolean("persist.sys.dchooks.enable", true);

    private static String[] getDeviceConfigsOverride() {
        String[] globalDeviceConfigs =
            Resources.getSystem().getStringArray(com.android.internal.R.array.global_device_configs_override);
        String[] deviceConfigs =
            Resources.getSystem().getStringArray(com.android.internal.R.array.device_configs_override);
        String[] allDeviceConfigs = Arrays.copyOf(globalDeviceConfigs, globalDeviceConfigs.length + deviceConfigs.length);
        System.arraycopy(deviceConfigs, 0, allDeviceConfigs, globalDeviceConfigs.length, deviceConfigs.length);
        return allDeviceConfigs;
    }

    public static boolean shouldDenyDeviceConfigControl(String namespace, String property) {
        if (!sEnableDeviceConfigUtils) return false;

        if (DEBUG) Log.d(TAG, "shouldAllowDeviceConfigControl, namespace=" + namespace + ", property=" + property);
        for (String p : getDeviceConfigsOverride()) {
            String[] kv = p.split("=");
            String fullKey = kv[0];
            String[] nsKey = fullKey.split("/");
            if (nsKey[0] == namespace && nsKey[1] == property){
                logd("shouldAllowDeviceConfigControl, deny, namespace=" + namespace + ", property=" + property);
                return true;
            }
        }
        logd("shouldAllowDeviceConfigControl, allow, namespace=" + namespace + ", property=" + property);
        return false;
    }

    public static void setDefaultProperties(String filterNamespace, String filterProperty) {
        if (!sEnableDeviceConfigUtils) return;

        logd("setDefaultProperties");
        // Group by namespace, read each namespace's current values in one call and write only
        // what differs. This runs on the system_server main thread at boot: writing all ~220
        // overrides one by one (each putString also re-scans the whole override list in
        // shouldDenyDeviceConfigControl) cost ~0.1 s per pass although nothing had changed.
        final Map<String, Map<String, String>> byNamespace = new LinkedHashMap<>();
        for (String p : getDeviceConfigsOverride()) {
            String[] kv = p.split("=");
            String fullKey = kv[0];
            String[] nsKey = fullKey.split("/");

            String namespace = nsKey[0];
            String key = nsKey[1];

            if (filterNamespace != null && filterNamespace == namespace){
                continue;
            }

            if (filterProperty != null && filterProperty == key){
                continue;
            }

            String value = "";
            if (kv.length > 1) {
                value = kv[1];
            }
            byNamespace.computeIfAbsent(namespace, n -> new LinkedHashMap<>()).put(key, value);
        }
        for (Map.Entry<String, Map<String, String>> ns : byNamespace.entrySet()) {
            final String namespace = ns.getKey();
            final Map<String, String> wanted = ns.getValue();
            Map<String, String> current;
            try {
                current = Settings.Config.getStrings(namespace, new ArrayList<>(wanted.keySet()));
            } catch (RuntimeException e) {
                current = null;
            }
            for (Map.Entry<String, String> e : wanted.entrySet()) {
                if (current != null && e.getValue().equals(current.get(e.getKey()))) {
                    continue;
                }
                Settings.Config.putString(namespace, e.getKey(), e.getValue(), false);
            }
        }
    }

    private static void logd(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }
}
