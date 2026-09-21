package com.escudo.app.clone;

import android.os.ParcelFileDescriptor;

/** Narrow cross-profile bridge. The Binder itself is only handed to the paired Escudo instance. */
interface ICloneBridge {
    ParcelFileDescriptor[] openApks(String packageName);
    boolean isInstalled(String packageName);
}
