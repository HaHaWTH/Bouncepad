package com.cleanroommc.bouncepad.api;

public final class JavaMetadata {

    public static String getVersionString() {
        var version = Runtime.version().toString();
        final int plus = version.indexOf('+');
        if (plus != -1) {
            version = version.substring(0, plus);
        }
        return version.replace('_', '.');
    }

    public static int getMajor() {
        return Runtime.version().version().get(0);
    }

}
