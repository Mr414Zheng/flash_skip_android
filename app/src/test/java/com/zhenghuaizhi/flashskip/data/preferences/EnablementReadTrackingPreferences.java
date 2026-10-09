package com.zhenghuaizhi.flashskip.data.preferences;

import androidx.datastore.preferences.core.Preferences;
import java.util.List;
import java.util.Map;

/** 测试专用：通过公开 JVM 构造器跟踪键读取，避免修改 DataStore 的生产接口。 */
public final class EnablementReadTrackingPreferences extends Preferences {
    private final Preferences values;
    private final List<String> readKeys;

    public EnablementReadTrackingPreferences(Preferences values, List<String> readKeys) {
        this.values = values;
        this.readKeys = readKeys;
    }

    @Override
    public <T> boolean contains(Key<T> key) {
        return values.contains(key);
    }

    @Override
    public <T> T get(Key<T> key) {
        readKeys.add(key.getName());
        if (key.getName().equals("action_records_v2")) {
            throw new AssertionError("Enablement checks must not read or decode execution records");
        }
        return values.get(key);
    }

    @Override
    public Map<Key<?>, Object> asMap() {
        return values.asMap();
    }
}
