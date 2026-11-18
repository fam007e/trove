package com.trove.privilege;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.util.List;

interface ITrovePrivileged {
    Bundle stat(String path);
    List<Bundle> listFiles(String path);
    ParcelFileDescriptor openFile(String path, String mode);
    boolean rename(String from, String to);
    boolean delete(String path);
    boolean mkdir(String path);
}
